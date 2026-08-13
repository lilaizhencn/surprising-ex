CREATE TABLE IF NOT EXISTS core_liquidation_projection (
    product_line VARCHAR(32) NOT NULL,
    liquidation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    asset VARCHAR(20) NOT NULL,
    position_side VARCHAR(16) NOT NULL,
    instrument_version BIGINT NOT NULL,
    trigger_price_sequence BIGINT NOT NULL,
    signed_quantity_steps BIGINT NOT NULL,
    close_quantity_steps BIGINT NOT NULL,
    deficit_units BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    export_sequence BIGINT NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL,
    PRIMARY KEY (product_line, liquidation_id)
);

CREATE INDEX IF NOT EXISTS idx_core_liquidation_pending
    ON core_liquidation_projection (product_line, status, liquidation_id);

CREATE INDEX IF NOT EXISTS idx_core_liquidation_user
    ON core_liquidation_projection (product_line, user_id, liquidation_id DESC);

CREATE TABLE IF NOT EXISTS core_treasury_projection (
    product_line VARCHAR(32) NOT NULL,
    asset VARCHAR(20) NOT NULL,
    fee_balance_units BIGINT NOT NULL,
    insurance_balance_units BIGINT NOT NULL,
    insurance_deficit_units BIGINT NOT NULL,
    export_sequence BIGINT NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL,
    PRIMARY KEY (product_line, asset)
);
