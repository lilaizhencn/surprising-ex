-- Minute buckets count committed account commands, including batches as one command.
CREATE TABLE IF NOT EXISTS core_trading_command_statistics (
    product_line varchar(32) NOT NULL,
    minute_epoch_ms bigint NOT NULL CHECK (minute_epoch_ms >= 0),
    user_id bigint NOT NULL CHECK (user_id > 0),
    command_type varchar(64) NOT NULL,
    command_status varchar(32) NOT NULL,
    result_code varchar(64) NOT NULL,
    commands bigint NOT NULL CHECK (commands > 0),
    PRIMARY KEY(product_line,minute_epoch_ms,user_id,command_type,command_status,result_code)
);
CREATE TABLE IF NOT EXISTS core_trading_statistics_cursor (
    product_line varchar(32) PRIMARY KEY,
    last_command_position bigint NOT NULL CHECK (last_command_position >= 0)
);
CREATE TABLE IF NOT EXISTS core_trading_statistics_initialization (
    product_line varchar(32) PRIMARY KEY,
    snapshot_log_position bigint NOT NULL CHECK (snapshot_log_position >= 0)
);
CREATE TABLE IF NOT EXISTS core_trading_statistics_snapshot (
    product_line varchar(32) NOT NULL,
    window_minutes integer NOT NULL CHECK (window_minutes IN (60,360,1440,10080)),
    cluster_position bigint NOT NULL CHECK (cluster_position >= 0),
    generated_at_epoch_ms bigint NOT NULL CHECK (generated_at_epoch_ms > 0),
    metrics jsonb NOT NULL,
    PRIMARY KEY(product_line,window_minutes)
);
