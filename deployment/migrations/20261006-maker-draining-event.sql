BEGIN;
ALTER TABLE market_maker_strategy_run_events DROP CONSTRAINT market_maker_run_events_type_check;
ALTER TABLE market_maker_strategy_run_events ADD CONSTRAINT market_maker_run_events_type_check CHECK (
    event_type IN ('CYCLE_SUCCESS', 'CYCLE_FAILED', 'QUOTE_RECONCILED', 'TRADE_SUBMITTED', 'TRADE_EXECUTED', 'TRADE_NO_FILL', 'TRADE_REJECTED', 'SKIPPED', 'STRATEGY_DRAINING')
) NOT VALID;
COMMIT;
ALTER TABLE market_maker_strategy_run_events VALIDATE CONSTRAINT market_maker_run_events_type_check;
