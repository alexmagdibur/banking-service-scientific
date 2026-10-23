-- MANUAL USE ONLY. The application never runs this file.
-- Deletes all transfers and restores the initial test balances (10 000 000 kopecks each).
--
--   psql -h localhost -U <user> -d <test database> -v ON_ERROR_STOP=1 -f scripts/reset-test-data.sql
BEGIN;

DELETE FROM transfers;

UPDATE accounts SET balance_minor = 10000000 WHERE id IN (1001, 1002, 1003);

COMMIT;
