import { bankingIteration, positiveInt } from './common.js';

const baseRate = positiveInt('BASE_RATE', 15);
const spikeRate = positiveInt('SPIKE_RATE', 40);
const before = positiveInt('BEFORE_SECONDS', 120);
const spike = positiveInt('SPIKE_SECONDS', 60);
const after = positiveInt('AFTER_SECONDS', 120);
const preAllocatedVUs = positiveInt('PREALLOCATED_VUS', 60);
const maxVUs = positiveInt('MAX_VUS', 120);
if (maxVUs < preAllocatedVUs) throw new Error('MAX_VUS must be >= PREALLOCATED_VUS');

function phase(rate, duration, startTime) {
  return {
    executor: 'constant-arrival-rate',
    exec: 'bankingIteration',
    rate,
    timeUnit: '1s',
    duration: `${duration}s`,
    startTime: `${startTime}s`,
    preAllocatedVUs,
    maxVUs,
    gracefulStop: '10s',
  };
}

export const options = {
  scenarios: {
    before: phase(baseRate, before, 0),
    spike: phase(spikeRate, spike, before),
    after: phase(baseRate, after, before + spike),
  },
  thresholds: { dropped_iterations: ['count==0'] },
};

export { bankingIteration };
