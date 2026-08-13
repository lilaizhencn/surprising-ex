ALTER TABLE trading_match_trades
    ADD COLUMN IF NOT EXISTS taker_fee_rate_ppm BIGINT NOT NULL DEFAULT 0;

ALTER TABLE trading_match_trades
    ADD COLUMN IF NOT EXISTS maker_fee_rate_ppm BIGINT NOT NULL DEFAULT 0;

ALTER TABLE trading_match_trades
    DROP CONSTRAINT IF EXISTS trading_match_trades_fee_rate_check;

ALTER TABLE trading_match_trades
    ADD CONSTRAINT trading_match_trades_fee_rate_check CHECK (
        taker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND maker_fee_rate_ppm BETWEEN -1000000 AND 1000000
    );
