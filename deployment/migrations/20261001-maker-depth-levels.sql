-- Match the maker service's validated 1..50 quote-level range.
BEGIN;
ALTER TABLE market_maker_strategy_overrides
    DROP CONSTRAINT market_maker_overrides_order_levels;
ALTER TABLE market_maker_strategy_overrides
    ADD CONSTRAINT market_maker_overrides_order_levels
    CHECK (order_levels IS NULL OR order_levels BETWEEN 1 AND 50);
COMMIT;
