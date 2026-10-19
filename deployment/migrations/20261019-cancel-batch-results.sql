-- 由 realtime 已提交回放写入逐项处理结果；不保存订单或账户状态副本。
ALTER TABLE core_command_result_projection
    ADD COLUMN IF NOT EXISTS cancel_batch_results jsonb
    CHECK (cancel_batch_results IS NULL OR (
        command_type = 'CANCEL_ORDER_BATCH' AND jsonb_typeof(cancel_batch_results) = 'array'
        AND jsonb_array_length(cancel_batch_results) BETWEEN 1 AND 50));

COMMENT ON COLUMN core_command_result_projection.cancel_batch_results IS
    '已提交批量撤单的逐项 index/orderId/success/code；最多 50 笔，NULL 表示尚无逐项事实，不表示撤单失败。';
