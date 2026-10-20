-- Realtime committed Archive replay owns the latest parent intent and authoritative child progress.
CREATE TABLE IF NOT EXISTS core_algo_order_projection (
    product_line varchar(32) NOT NULL,
    algo_order_id bigint NOT NULL CHECK (algo_order_id > 0),
    user_id bigint NOT NULL CHECK (user_id > 0),
    client_algo_order_id varchar(64) NOT NULL,
    instrument_id varchar(64) NOT NULL,
    status_code integer NOT NULL CHECK (status_code BETWEEN 0 AND 5),
    created_at_epoch_ms bigint NOT NULL CHECK (created_at_epoch_ms > 0),
    updated_at_epoch_ms bigint NOT NULL CHECK (updated_at_epoch_ms > 0),
    revision bigint NOT NULL CHECK (revision > 0),
    export_sequence bigint NOT NULL CHECK (export_sequence >= 0),
    raw_algo_state bytea NOT NULL,
    PRIMARY KEY(product_line,algo_order_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_algo_projection_client
    ON core_algo_order_projection(product_line,user_id,client_algo_order_id);
CREATE INDEX IF NOT EXISTS idx_algo_projection_open
    ON core_algo_order_projection(product_line,user_id,created_at_epoch_ms DESC,algo_order_id DESC)
    WHERE status_code IN (0,1,2);
CREATE TABLE IF NOT EXISTS core_algo_projection_initialization (
    product_line varchar(32) PRIMARY KEY,
    snapshot_log_position bigint NOT NULL CHECK (snapshot_log_position >= 0),
    initialized_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
