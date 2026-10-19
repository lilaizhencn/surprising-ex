-- realtime owns this reconstructable latest-state projection, including terminal trigger orders.
CREATE TABLE IF NOT EXISTS core_trigger_order_projection (
    product_line varchar(32) NOT NULL,
    trigger_order_id bigint NOT NULL CHECK (trigger_order_id > 0),
    user_id bigint NOT NULL CHECK (user_id > 0),
    client_trigger_order_id varchar(64) NOT NULL,
    instrument_id varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    created_at_epoch_ms bigint NOT NULL CHECK (created_at_epoch_ms > 0),
    updated_at_epoch_ms bigint NOT NULL CHECK (updated_at_epoch_ms > 0),
    revision bigint NOT NULL CHECK (revision > 0),
    export_sequence bigint NOT NULL CHECK (export_sequence >= 0),
    raw_trigger_state bytea NOT NULL,
    PRIMARY KEY (product_line, trigger_order_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_trigger_projection_client
    ON core_trigger_order_projection(product_line,user_id,client_trigger_order_id)
    WHERE client_trigger_order_id <> '';
CREATE INDEX IF NOT EXISTS idx_trigger_projection_created
    ON core_trigger_order_projection(product_line,created_at_epoch_ms,trigger_order_id);
CREATE INDEX IF NOT EXISTS idx_trigger_projection_user_created
    ON core_trigger_order_projection(product_line,user_id,created_at_epoch_ms,trigger_order_id);
CREATE INDEX IF NOT EXISTS idx_trigger_projection_instrument_created
    ON core_trigger_order_projection(product_line,instrument_id,created_at_epoch_ms,trigger_order_id);
CREATE INDEX IF NOT EXISTS idx_trigger_projection_status_created
    ON core_trigger_order_projection(product_line,status,created_at_epoch_ms,trigger_order_id);

-- Presence proves that all retained states in the export checkpoint were reconstructed.
-- A partially written bootstrap must not be exposed as an empty/complete query result.
CREATE TABLE IF NOT EXISTS core_trigger_projection_initialization (
    product_line varchar(32) PRIMARY KEY,
    snapshot_log_position bigint NOT NULL CHECK (snapshot_log_position >= 0),
    initial_export_sequence bigint NOT NULL CHECK (initial_export_sequence >= 0),
    initialized_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
