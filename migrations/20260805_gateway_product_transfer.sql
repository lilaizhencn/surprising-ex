CREATE SEQUENCE IF NOT EXISTS gateway_product_transfer_seq;

CREATE TABLE IF NOT EXISTS gateway_product_transfers (
    transfer_id          BIGINT PRIMARY KEY DEFAULT nextval('gateway_product_transfer_seq'),
    user_id              BIGINT NOT NULL REFERENCES gateway_users(user_id),
    idempotency_key      VARCHAR(128) NOT NULL,
    request_fingerprint  VARCHAR(64) NOT NULL,
    source_account_type  VARCHAR(32) NOT NULL,
    target_account_type  VARCHAR(32) NOT NULL,
    asset                VARCHAR(20) NOT NULL,
    amount_units         BIGINT NOT NULL CHECK (amount_units > 0),
    reference_id         VARCHAR(128) NOT NULL,
    reason               VARCHAR(128) NOT NULL DEFAULT '',
    status               VARCHAR(32) NOT NULL,
    error_code           VARCHAR(64),
    error_message        VARCHAR(512),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at         TIMESTAMPTZ,
    CONSTRAINT gateway_product_transfers_status_check CHECK (
        status IN ('PENDING', 'SOURCE_DEBIT_UNKNOWN', 'SOURCE_DEBITED', 'TARGET_CREDIT_UNKNOWN',
                   'COMPENSATION_REQUIRED', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT gateway_product_transfers_accounts_check CHECK (source_account_type <> target_account_type)
);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_product_transfers_user_key_uidx
    ON gateway_product_transfers (user_id, idempotency_key);
CREATE INDEX IF NOT EXISTS gateway_product_transfers_status_idx
    ON gateway_product_transfers (status, updated_at);
CREATE INDEX IF NOT EXISTS gateway_product_transfers_user_time_idx
    ON gateway_product_transfers (user_id, created_at DESC, transfer_id DESC);
