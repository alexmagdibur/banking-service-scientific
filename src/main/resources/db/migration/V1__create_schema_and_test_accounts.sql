-- Money is stored as integer kopecks (BIGINT). All timestamps are TIMESTAMPTZ (UTC on the wire).
CREATE TABLE accounts (
    id            BIGINT       PRIMARY KEY,
    display_name  VARCHAR(100) NOT NULL,
    balance_minor BIGINT       NOT NULL CONSTRAINT accounts_balance_non_negative CHECK (balance_minor >= 0)
);

CREATE TABLE transfers (
    id                  UUID         PRIMARY KEY,
    idempotency_key     VARCHAR(128) NOT NULL CONSTRAINT transfers_idempotency_key_unique UNIQUE,
    from_account_id     BIGINT       NOT NULL CONSTRAINT transfers_from_account_fk REFERENCES accounts (id),
    to_account_id       BIGINT       NOT NULL CONSTRAINT transfers_to_account_fk REFERENCES accounts (id),
    amount_minor        BIGINT       NOT NULL CONSTRAINT transfers_amount_positive CHECK (amount_minor > 0),
    request_fingerprint VARCHAR(64)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT transfers_distinct_accounts CHECK (from_account_id <> to_account_id)
);

CREATE INDEX transfers_created_at_id_idx ON transfers (created_at DESC, id DESC);

-- Test data, inserted once when the database is created: 3 accounts x 10 000 000 kopecks (100 000 RUB).
INSERT INTO accounts (id, display_name, balance_minor) VALUES
    (1001, 'Счёт А', 10000000),
    (1002, 'Счёт Б', 10000000),
    (1003, 'Счёт В', 10000000);
