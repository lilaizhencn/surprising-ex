ALTER TABLE core_execution_projection
    ADD COLUMN IF NOT EXISTS symbol VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE core_execution_projection
    ADD COLUMN IF NOT EXISTS instrument_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE core_execution_projection
    ADD COLUMN IF NOT EXISTS taker_side VARCHAR(8) NOT NULL DEFAULT 'BUY';

ALTER TABLE core_execution_projection
    ADD COLUMN IF NOT EXISTS taker_fee_rate_ppm BIGINT NOT NULL DEFAULT 0;

ALTER TABLE core_execution_projection
    ADD COLUMN IF NOT EXISTS maker_fee_rate_ppm BIGINT NOT NULL DEFAULT 0;

ALTER TABLE core_execution_projection
    ADD COLUMN IF NOT EXISTS occurred_at_epoch_ms BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_core_execution_projection_symbol_time
    ON core_execution_projection (product_line, symbol, occurred_at_epoch_ms DESC, export_sequence DESC);
