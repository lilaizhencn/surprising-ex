ALTER TABLE trading_trigger_orders
    DROP CONSTRAINT IF EXISTS trading_trigger_orders_placed_order_fk;

ALTER TABLE trading_order_events
    DROP CONSTRAINT IF EXISTS trading_order_events_order_fk;

ALTER TABLE trading_match_results
    DROP CONSTRAINT IF EXISTS trading_match_results_order_fk;

ALTER TABLE account_spot_order_reservations
    DROP CONSTRAINT IF EXISTS account_spot_reservations_order_fk;
