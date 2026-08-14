ALTER TABLE core_liquidation_projection
    ADD COLUMN IF NOT EXISTS margin_mode VARCHAR(16) NOT NULL DEFAULT 'CROSS';

ALTER TABLE core_liquidation_projection
    ADD COLUMN IF NOT EXISTS execution_price_ticks BIGINT NOT NULL DEFAULT 0;

ALTER TABLE core_liquidation_projection
    ADD COLUMN IF NOT EXISTS liquidation_fee_rate_ppm BIGINT NOT NULL DEFAULT 0;

ALTER TABLE core_liquidation_projection
    ADD COLUMN IF NOT EXISTS liquidation_fee_units BIGINT NOT NULL DEFAULT 0;
