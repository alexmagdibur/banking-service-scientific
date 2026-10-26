import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';

const dir = process.argv[2];
if (!dir) throw new Error('Usage: node analyze_experiment_run.mjs RUN_DIRECTORY');
const summary = JSON.parse(fs.readFileSync(path.join(dir, 'summary.json'), 'utf8'));
const buckets = new Map();
let firstStart = Infinity;
let lastStart = -Infinity;
let rawStarted = 0;
let rawSuccess = 0;

function bucket(sec) {
  if (!buckets.has(sec)) buckets.set(sec, { started: 0, completed: 0, ok: 0, balanceLatencies: [] });
  return buckets.get(sec);
}
const input = readline.createInterface({ input: fs.createReadStream(path.join(dir, 'raw.json')), crlfDelay: Infinity });
for await (const line of input) {
  if (!line.includes('"type":"Point"')) continue;
  if (!line.includes('"metric":"bank_operations_started"') &&
      !line.includes('"metric":"bank_operation_success"') &&
      !line.includes('"metric":"bank_balance_success_ms"')) continue;
  const p = JSON.parse(line);
  const millis = Date.parse(p.data.time);
  const b = bucket(Math.floor(millis / 1000));
  if (p.metric === 'bank_operations_started') {
    b.started += p.data.value;
    rawStarted += p.data.value;
    firstStart = Math.min(firstStart, millis);
    lastStart = Math.max(lastStart, millis);
  } else if (p.metric === 'bank_operation_success') {
    b.completed += 1;
    b.ok += p.data.value;
    rawSuccess += p.data.value;
  } else if (p.metric === 'bank_balance_success_ms') {
    b.balanceLatencies.push(p.data.value);
  }
}
if (!Number.isFinite(firstStart)) throw new Error('No started operation points');
function percentile(values, p) {
  if (values.length === 0) return null;
  const sorted = values.toSorted((a, b) => a - b);
  const pos = Math.ceil(p * sorted.length) - 1;
  return sorted[Math.max(0, Math.min(pos, sorted.length - 1))];
}

const clockOffsets = ['clock-before.csv', 'clock-after.csv'].flatMap(file => {
  const full = path.join(dir, file);
  if (!fs.existsSync(full)) return [];
  return fs.readFileSync(full, 'utf8').trim().split(/\r?\n/).slice(1)
    .map(line => Number(line.split(',')[3])).filter(Number.isFinite);
});
if (clockOffsets.length === 0) throw new Error('Missing VM-to-generator clock checks');
const vmAheadMs = clockOffsets.reduce((sum, x) => sum + x, 0) / clockOffsets.length * 1000;

let eventWindowsTime = null;
const eventFile = ['deletion-event.txt', 'fault-event.txt', 'spike-event.txt']
  .map(name => path.join(dir, name)).find(full => fs.existsSync(full));
if (eventFile) {
  const eventText = fs.readFileSync(eventFile, 'utf8');
  const actualFaultStart = eventFile.endsWith('fault-event.txt')
    ? eventText.match(/"startsAt":"([^"]+)"/)
    : null;
  const scheduled = eventText.match(/target_event_time_utc=(\S+)/);
  if (actualFaultStart) eventWindowsTime = Date.parse(actualFaultStart[1]) - vmAheadMs;
  else if (scheduled) eventWindowsTime = Date.parse(scheduled[1]);
}
const series = [];
const fromSec = Math.floor(firstStart / 1000) + 5;
const toSec = Math.floor(lastStart / 1000) + 1;
for (let sec = fromSec; sec <= toSec; sec++) {
  let started = 0, completed = 0, ok = 0;
  const balanceLatencies = [];
  for (let bsec = sec - 5; bsec < sec; bsec++) {
    const b = buckets.get(bsec);
    if (!b) continue;
    started += b.started;
    completed += b.completed;
    ok += b.ok;
    balanceLatencies.push(...b.balanceLatencies);
  }
  const successFraction = completed ? ok / completed : null;
  const latencyP95 = percentile(balanceLatencies, 0.95);
  let state = 'healthy';
  if (started < 5) state = 'insufficient';
  else if (completed < 5 || successFraction < 0.99 || (latencyP95 !== null && latencyP95 > 1000)) state = 'bad';
  else if (balanceLatencies.length < 5) state = 'insufficient';
  series.push({ time: new Date(sec * 1000).toISOString(), started, completed, ok, successFraction, balanceSamples: balanceLatencies.length, latencyP95, state });
}
let lossIndex = -1;
if (eventWindowsTime !== null) {
  lossIndex = series.findIndex(x => Date.parse(x.time) >= eventWindowsTime && x.state === 'bad');
} else {
  lossIndex = series.findIndex(x => x.state === 'bad');
}
let restoreIndex = -1;
if (lossIndex >= 0) {
  for (let i = lossIndex + 1; i <= series.length - 10; i++) {
    if (series.slice(i, i + 10).every(x => x.state === 'healthy')) { restoreIndex = i; break; }
  }
}

const resourcePath = path.join(dir, 'cluster', 'pod_resources.csv');
const resourceLines = fs.readFileSync(resourcePath, 'utf8').trim().split(/\r?\n/).slice(1);
const cpuByTime = new Map();
const memByTime = new Map();
for (const line of resourceLines) {
  const [timestamp, pod, cpu, memory] = line.split(',');
  if (!pod.startsWith('bank-api-')) continue;
  const ms = Date.parse(timestamp) - vmAheadMs;
  const cores = cpu.endsWith('m') ? Number(cpu.slice(0, -1)) / 1000 : Number(cpu);
  const mib = memory.endsWith('Mi') ? Number(memory.slice(0, -2)) : Number(memory);
  cpuByTime.set(ms, (cpuByTime.get(ms) || 0) + cores);
  memByTime.set(ms, (memByTime.get(ms) || 0) + mib);
}
const samples = [...cpuByTime.keys()].sort((a,b) => a-b);
const integrationEnd = firstStart + 300000;
if (samples.length === 0) throw new Error('No bank-api resource samples');
let cpuCoreSeconds = 0, memoryMiBSeconds = 0, coveredSeconds = 0, maxSampleGapSeconds = 0;
let boundaryExtrapolatedSeconds = 0;
if (samples[0] > firstStart) {
  const seconds = (Math.min(samples[0], integrationEnd) - firstStart) / 1000;
  if (seconds > 0) {
    cpuCoreSeconds += (cpuByTime.get(samples[0]) || 0) * seconds;
    memoryMiBSeconds += (memByTime.get(samples[0]) || 0) * seconds;
    coveredSeconds += seconds;
    boundaryExtrapolatedSeconds += seconds;
  }
}
for (let i = 0; i < samples.length - 1; i++) {
  const a = samples[i], b = samples[i+1];
  const left = Math.max(a, firstStart), right = Math.min(b, integrationEnd);
  if (right <= left) continue;
  const seconds = (right-left)/1000;
  coveredSeconds += seconds;
  maxSampleGapSeconds = Math.max(maxSampleGapSeconds, (b-a)/1000);
  cpuCoreSeconds += (cpuByTime.get(a) || 0)*seconds;
  memoryMiBSeconds += (memByTime.get(a) || 0)*seconds;
}
if (samples.at(-1) < integrationEnd) {
  const seconds = (integrationEnd - Math.max(samples.at(-1), firstStart)) / 1000;
  if (seconds > 0) {
    cpuCoreSeconds += (cpuByTime.get(samples.at(-1)) || 0) * seconds;
    memoryMiBSeconds += (memByTime.get(samples.at(-1)) || 0) * seconds;
    coveredSeconds += seconds;
    boundaryExtrapolatedSeconds += seconds;
  }
}

const result = {
  runId: path.basename(dir),
  firstStartedUtc: new Date(firstStart).toISOString(),
  lastStartedUtc: new Date(lastStart).toISOString(),
  counts: {
    rawStarted,
    rawSuccess,
    summaryStarted: summary.metrics.bank_operations_started?.count,
    summarySuccess: summary.metrics.bank_operations_ok?.count,
    dropped: summary.metrics.dropped_iterations?.count || 0,
  },
  A: rawSuccess / rawStarted,
  balanceSuccessfulP95Ms: summary.metrics.bank_balance_success_ms?.['p(95)'],
  eventTargetUtc: eventWindowsTime === null ? null : new Date(eventWindowsTime).toISOString(),
  quality: {
    samplingStepSeconds: 1,
    rollingWindowSeconds: 5,
    successThreshold: 0.99,
    latencyThresholdMs: 1000,
    minimumCompleted: 5,
    minimumSuccessfulBalanceSamples: 5,
    stableRestoreSeconds: 10,
    lossUtc: lossIndex < 0 ? null : series[lossIndex].time,
    restoreUtc: restoreIndex < 0 ? null : series[restoreIndex].time,
    recoverySeconds: lossIndex < 0 ? null : restoreIndex < 0 ? null : (Date.parse(series[restoreIndex].time) - Date.parse(series[lossIndex].time)) / 1000,
    badWindowsBeforeEvent: eventWindowsTime === null ? null : series.filter(x => Date.parse(x.time) < eventWindowsTime && x.state === 'bad').length,
    insufficientWindows: series.filter(x => x.state === 'insufficient').length,
  },
  resource: {
    vmAheadOfGeneratorSeconds: vmAheadMs / 1000,
    appOnlyCpuCoreSeconds: cpuCoreSeconds,
    appOnlyMemoryMiBSeconds: memoryMiBSeconds,
    coveredSeconds,
    boundaryExtrapolatedSeconds,
    maxSampleGapSeconds,
    sampleCount: samples.length,
    integrationWindowSeconds: 300,
  },
};
fs.writeFileSync(path.join(dir, 'analysis.json'), JSON.stringify(result, null, 2) + '\n');
fs.writeFileSync(path.join(dir, 'quality_windows.json'), JSON.stringify(series, null, 2) + '\n');
console.log(JSON.stringify(result, null, 2));
