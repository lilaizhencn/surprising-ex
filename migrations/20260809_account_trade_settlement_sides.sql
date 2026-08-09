BEGIN;

CREATE TABLE IF NOT EXISTS account_trade_settlement_sides (
    product_line                   TEXT NOT NULL,
    symbol                         TEXT NOT NULL,
    trade_id                       BIGINT NOT NULL,
    participant_role               TEXT NOT NULL,
    taker_user_id                  BIGINT NOT NULL,
    maker_user_id                  BIGINT NOT NULL,
    command_id                     VARCHAR(160) NOT NULL,
    order_id                       BIGINT NOT NULL,
    order_margin_consumed_units   BIGINT NOT NULL DEFAULT 0,
    order_margin_released_units   BIGINT NOT NULL DEFAULT 0,
    applied_at                     TIMESTAMPTZ NOT NULL,
    reconciled_at                 TIMESTAMPTZ,
    PRIMARY KEY (product_line, symbol, trade_id, participant_role),
    CONSTRAINT account_trade_settlement_sides_command_uk UNIQUE (product_line, command_id),
    CONSTRAINT account_trade_settlement_sides_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_trade_settlement_sides_users_positive CHECK (
        taker_user_id > 0 AND maker_user_id > 0
    ),
    CONSTRAINT account_trade_settlement_sides_margin_non_negative CHECK (
        order_id > 0 AND order_margin_consumed_units >= 0 AND order_margin_released_units >= 0
    ),
    CONSTRAINT account_trade_settlement_sides_role_check CHECK (participant_role IN ('TAKER', 'MAKER'))
);

ALTER TABLE account_trade_settlement_sides
    ADD COLUMN IF NOT EXISTS order_id BIGINT;
ALTER TABLE account_trade_settlement_sides
    ADD COLUMN IF NOT EXISTS order_margin_consumed_units BIGINT NOT NULL DEFAULT 0;
ALTER TABLE account_trade_settlement_sides
    ADD COLUMN IF NOT EXISTS order_margin_released_units BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS account_trade_settlement_sides_monitor_idx
    ON account_trade_settlement_sides (product_line, applied_at, symbol, trade_id)
    WHERE reconciled_at IS NULL;

CREATE OR REPLACE VIEW account_trade_settlement_completions AS
SELECT product_line,
       symbol,
       trade_id,
       MAX(taker_user_id) AS taker_user_id,
       MAX(maker_user_id) AS maker_user_id,
       MIN(applied_at) AS first_applied_at,
       MAX(applied_at) AS completed_at
  FROM account_trade_settlement_sides
 GROUP BY product_line, symbol, trade_id
HAVING COUNT(*) = 2
   AND COUNT(DISTINCT participant_role) = 2;

COMMIT;
