-- 在相关旧应用停止后执行，再启动不再读写追踪元数据的新版本。
-- 保留业务记录、审批状态、请求摘要、订单和资金标识；仅删除追踪字段及其索引。
BEGIN;

ALTER TABLE IF EXISTS market_maker_strategy_run_events DROP COLUMN IF EXISTS trace_id;
ALTER TABLE IF EXISTS market_maker_reference_samples DROP COLUMN IF EXISTS trace_id;
ALTER TABLE IF EXISTS gateway_admin_operation_logs DROP COLUMN IF EXISTS trace_id;
ALTER TABLE IF EXISTS gateway_admin_approval_requests DROP COLUMN IF EXISTS consumed_trace_id;

COMMIT;
