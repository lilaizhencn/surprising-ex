-- realtime 重放已提交日志后写入的处理回执。不是 Core 内存缓存，也不表示订单当前状态。
CREATE TABLE IF NOT EXISTS core_command_result_projection (
    product_line varchar(32) NOT NULL,
    command_id uuid NOT NULL,
    user_id bigint NOT NULL CHECK (user_id > 0),
    command_type varchar(64) NOT NULL,
    client_order_id varchar(64),
    instrument_id varchar(64),
    command_status varchar(32) NOT NULL,
    result_code varchar(64) NOT NULL,
    applied_command_count bigint NOT NULL CHECK (applied_command_count >= 0),
    cluster_position bigint NOT NULL CHECK (cluster_position > 0),
    occurred_at_epoch_ms bigint NOT NULL CHECK (occurred_at_epoch_ms > 0),
    PRIMARY KEY (product_line, command_id),
    CHECK (result_code <> 'MATCHING_PENDING'),
    CHECK (command_status IN ('APPLIED', 'REJECTED'))
);
CREATE INDEX IF NOT EXISTS idx_core_command_result_user
    ON core_command_result_projection (product_line, user_id, command_id);
