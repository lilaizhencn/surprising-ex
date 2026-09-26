-- 启动定价明确记录缺失输入，不修改资金和订单。
BEGIN;
ALTER TABLE price_mark_ticks
    ALTER COLUMN price2 DROP NOT NULL,
    ALTER COLUMN last_trade_price DROP NOT NULL,
    ALTER COLUMN best_bid_price DROP NOT NULL,
    ALTER COLUMN best_ask_price DROP NOT NULL,
    ALTER COLUMN basis_average DROP NOT NULL;
ALTER TABLE price_mark_ticks ADD CONSTRAINT price_mark_ticks_complete_inputs CHECK (
    status = 'DEGRADED' OR (price2 IS NOT NULL AND last_trade_price IS NOT NULL
    AND best_bid_price IS NOT NULL AND best_ask_price IS NOT NULL AND basis_average IS NOT NULL)
);
COMMIT;
