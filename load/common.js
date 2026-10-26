import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || '').replace(/\/$/, '');
const requestTimeout = __ENV.TIMEOUT || '3s';
const runId = __ENV.RUN_ID || '';

if (!baseUrl.startsWith('http://') && !baseUrl.startsWith('https://')) {
  throw new Error('Set BASE_URL to the Kubernetes NodePort URL');
}
if (!runId || runId.length > 60 || !/^[A-Za-z0-9_-]+$/.test(runId)) {
  throw new Error('Set a unique RUN_ID (1..60 letters, digits, underscore or hyphen) for each run');
}

const started = new Counter('bank_operations_started');
const succeeded = new Counter('bank_operations_ok');
const successWindow = new Rate('bank_operation_success');
const balanceLatency = new Trend('bank_balance_success_ms', true);
const transferLatency = new Trend('bank_transfer_success_ms', true);
const businessErrors = new Counter('bank_business_errors');
const technicalErrors = new Counter('bank_technical_errors');
const timeouts = new Counter('bank_timeouts');
const networkErrors = new Counter('bank_network_errors');
const semanticErrors = new Counter('bank_semantic_errors');

function responseJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

export function bankingIteration() {
  const iteration = exec.scenario.iterationInTest;
  const isTransfer = iteration % 5 === 4; // exactly one intended transfer per five iterations
  const operation = isTransfer ? 'transfer' : 'balance_view';
  const tags = { operation };
  const startedAt = Date.now();
  started.add(1, tags);

  let response;
  let valid = false;
  let idempotencyKey = null;

  if (isTransfer) {
    const transferNumber = Math.floor(iteration / 5);
    const fromAccountId = transferNumber % 2 === 0 ? 1001 : 1002;
    const toAccountId = fromAccountId === 1001 ? 1002 : 1001;
    idempotencyKey = `${runId}-${exec.scenario.name}-${iteration}`;
    response = http.post(
      `${baseUrl}/api/transfers`,
      JSON.stringify({ fromAccountId, toAccountId, amountMinor: 100 }),
      {
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
        timeout: requestTimeout,
        tags: { name: 'POST /api/transfers', operation },
      },
    );
    const body = response.status === 201 ? responseJson(response) : null;
    valid = response.status === 201 && body !== null &&
      typeof body.id === 'string' && body.status === 'COMPLETED' &&
      body.fromAccountId === fromAccountId && body.toAccountId === toAccountId &&
      body.amountMinor === 100;
  } else {
    response = http.get(`${baseUrl}/api/accounts/1001`, {
      timeout: requestTimeout,
      tags: { name: 'GET /api/accounts/1001', operation },
    });
    const body = response.status === 200 ? responseJson(response) : null;
    valid = response.status === 200 && body !== null &&
      body.id === 1001 && Number.isInteger(body.balanceMinor) && body.balanceMinor >= 0;
  }

  // Wall-clock client latency includes connection establishment; k6's built-in
  // http_req_duration excludes the initial connection, so keep this separate.
  const elapsedMs = Date.now() - startedAt;
  successWindow.add(valid ? 1 : 0, tags);
  if (valid) {
    succeeded.add(1, tags);
    if (isTransfer) transferLatency.add(elapsedMs, tags);
    else balanceLatency.add(elapsedMs, tags);
    return;
  }

  if (response.error_code === 1050 || response.error_code === 1211) {
    timeouts.add(1, tags);
  } else if (response.status === 0) {
    networkErrors.add(1, tags);
  } else if (response.status >= 500) {
    technicalErrors.add(1, tags);
  } else if (response.status >= 400) {
    businessErrors.add(1, tags);
  } else {
    semanticErrors.add(1, tags);
  }

  // A failed response does not prove that a transfer was not committed. Save
  // its test-only key in the runner log for later reconciliation if needed.
  if (isTransfer && (response.status === 0 || response.status >= 500)) {
    console.warn(`uncertain_transfer run=${runId} scenario=${exec.scenario.name} key=${idempotencyKey} status=${response.status} error_code=${response.error_code || 0}`);
  }
}

export function positiveInt(name, fallback) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isInteger(value) || value <= 0) throw new Error(`${name} must be a positive integer`);
  return value;
}
