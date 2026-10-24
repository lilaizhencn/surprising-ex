-- The current realtime exporter has not written the former execution table.
-- Existing populated tables require an explicit historical rebuild before this migration;
-- never synthesize public trade IDs or position sides from unrelated current state.
ALTER TABLE core_execution_projection ADD COLUMN IF NOT EXISTS trade_id text NOT NULL;
ALTER TABLE core_execution_projection ADD COLUMN IF NOT EXISTS taker_position_side varchar(16) NOT NULL;
ALTER TABLE core_execution_projection ADD COLUMN IF NOT EXISTS maker_position_side varchar(16) NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_core_execution_projection_trade
    ON core_execution_projection(product_line,trade_id);
CREATE INDEX IF NOT EXISTS idx_core_execution_projection_time
    ON core_execution_projection(product_line,occurred_at_epoch_ms,export_sequence,execution_index);
CREATE INDEX IF NOT EXISTS idx_core_execution_projection_taker_order
    ON core_execution_projection(product_line,taker_order_id,occurred_at_epoch_ms,export_sequence,execution_index);
CREATE INDEX IF NOT EXISTS idx_core_execution_projection_maker_order
    ON core_execution_projection(product_line,maker_order_id,occurred_at_epoch_ms,export_sequence,execution_index);

CREATE TABLE IF NOT EXISTS core_execution_projection_initialization (
    product_line varchar(32) PRIMARY KEY,
    snapshot_log_position bigint NOT NULL CHECK (snapshot_log_position >= 0),
    initialized_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
