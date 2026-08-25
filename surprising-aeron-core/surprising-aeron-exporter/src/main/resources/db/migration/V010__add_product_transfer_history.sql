CREATE TABLE IF NOT EXISTS account_product_transfers (
    transfer_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source_account_type VARCHAR(32) NOT NULL,
    target_account_type VARCHAR(32) NOT NULL,
    asset VARCHAR(20) NOT NULL,
    amount_units BIGINT NOT NULL,
    reference_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT account_product_transfers_user_positive CHECK (user_id > 0),
    CONSTRAINT account_product_transfers_amount_positive CHECK (amount_units > 0),
    CONSTRAINT account_product_transfers_accounts_check CHECK (source_account_type <> target_account_type),
    CONSTRAINT account_product_transfers_status_check CHECK (status = 'COMPLETED')
);

CREATE INDEX IF NOT EXISTS account_product_transfers_user_time_idx
    ON account_product_transfers (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS account_product_transfers_reference_idx
    ON account_product_transfers (user_id, reference_id);
