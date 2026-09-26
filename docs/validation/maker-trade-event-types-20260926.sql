-- 本地及已有数据库：仅扩展 maker 审计事件类型，不修改订单或资金。
BEGIN;
ALTER TABLE market_maker_strategy_run_events
    DROP CONSTRAINT market_maker_run_events_type_check;
ALTER TABLE market_maker_strategy_run_events
    ADD CONSTRAINT market_maker_run_events_type_check CHECK (
        event_type IN ('CYCLE_SUCCESS', 'CYCLE_FAILED', 'QUOTE_RECONCILED',
                       'TRADE_SUBMITTED', 'TRADE_EXECUTED', 'TRADE_NO_FILL', 'TRADE_REJECTED', 'SKIPPED')
    );
COMMIT;
