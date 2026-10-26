import { bankingIteration, positiveInt } from './common.js';

const rate = positiveInt('RATE', 20);
const duration = positiveInt('DURATION_SECONDS', 300);
const preAllocatedVUs = positiveInt('PREALLOCATED_VUS', 60);
const maxVUs = positiveInt('MAX_VUS', 120);
if (maxVUs < preAllocatedVUs) throw new Error('MAX_VUS must be >= PREALLOCATED_VUS');

export const options = {
  scenarios: {
    constant: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: `${duration}s`,
      preAllocatedVUs,
      maxVUs,
      gracefulStop: '10s',
    },
  },
  thresholds: { dropped_iterations: ['count==0'] },
};

export default bankingIteration;
