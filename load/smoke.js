import http from 'k6/http';
import { check } from 'k6';

const baseUrl = (__ENV.BASE_URL || '').replace(/\/$/, '');
if (!baseUrl.startsWith('http://') && !baseUrl.startsWith('https://')) {
  throw new Error('Set BASE_URL to the Kubernetes NodePort URL');
}

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

export default function () {
  const accounts = http.get(`${baseUrl}/api/accounts`, { timeout: '5s' });
  let initial = null;
  try { initial = accounts.json(); } catch (_) { /* check below records failure */ }
  check(accounts, {
    'three initial accounts': (r) => r.status === 200 && Array.isArray(initial) && initial.length === 3,
  });

  const key = `smoke-${Date.now()}-${__VU}`;
  const body = JSON.stringify({ fromAccountId: 1001, toAccountId: 1002, amountMinor: 100 });
  const params = {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
    timeout: '5s',
  };
  const created = http.post(`${baseUrl}/api/transfers`, body, params);
  let transfer = null;
  try { transfer = created.json(); } catch (_) { /* check below records failure */ }
  check(created, {
    'transfer created': (r) => r.status === 201 && transfer && transfer.status === 'COMPLETED' && transfer.amountMinor === 100,
  });
  if (!transfer || !transfer.id) return;

  const fetched = http.get(`${baseUrl}/api/transfers/${transfer.id}`, { timeout: '5s' });
  check(fetched, {
    'transfer is readable': (r) => r.status === 200 && r.json('id') === transfer.id,
  });

  const replayed = http.post(`${baseUrl}/api/transfers`, body, params);
  check(replayed, {
    'idempotent replay': (r) => r.status === 200 && r.json('id') === transfer.id,
  });

  const after = http.get(`${baseUrl}/api/accounts`, { timeout: '5s' });
  let accountsAfter = null;
  try { accountsAfter = after.json(); } catch (_) { /* check below records failure */ }
  check(after, {
    'total balance conserved': (r) => r.status === 200 && Array.isArray(accountsAfter) &&
      accountsAfter.reduce((sum, account) => sum + account.balanceMinor, 0) === 30000000,
  });
}
