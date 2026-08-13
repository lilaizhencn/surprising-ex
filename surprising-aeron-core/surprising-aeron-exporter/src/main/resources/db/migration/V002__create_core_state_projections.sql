CREATE TABLE IF NOT EXISTS core_user_fact_projection (
    product_line VARCHAR(32) NOT NULL,
    export_sequence BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    user_revision BIGINT NOT NULL,
    raw_user_delta BYTEA NOT NULL,
    PRIMARY KEY (product_line, export_sequence, user_id)
);

CREATE TABLE IF NOT EXISTS core_order_projection (
    product_line VARCHAR(32) NOT NULL,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    client_order_id VARCHAR(64),
    symbol VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at_epoch_ms BIGINT NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL,
    cluster_position BIGINT NOT NULL,
    order_revision BIGINT NOT NULL,
    export_sequence BIGINT NOT NULL,
    raw_order_state BYTEA NOT NULL,
    PRIMARY KEY (product_line, order_id),
    UNIQUE (product_line, user_id, client_order_id)
);

CREATE INDEX IF NOT EXISTS idx_core_order_projection_user_status
    ON core_order_projection (product_line, user_id, status, order_id DESC);

CREATE INDEX IF NOT EXISTS idx_core_order_projection_symbol_status
    ON core_order_projection (product_line, symbol, status, order_id DESC);

CREATE TABLE IF NOT EXISTS core_execution_projection (
    product_line VARCHAR(32) NOT NULL,
    export_sequence BIGINT NOT NULL,
    execution_index INTEGER NOT NULL,
    taker_order_id BIGINT NOT NULL,
    maker_order_id BIGINT NOT NULL,
    taker_user_id BIGINT NOT NULL,
    maker_user_id BIGINT NOT NULL,
    price_ticks BIGINT NOT NULL,
    quantity_steps BIGINT NOT NULL,
    PRIMARY KEY (product_line, export_sequence, execution_index)
);

CREATE INDEX IF NOT EXISTS idx_core_execution_projection_taker
    ON core_execution_projection (product_line, taker_user_id, export_sequence DESC);

CREATE INDEX IF NOT EXISTS idx_core_execution_projection_maker
    ON core_execution_projection (product_line, maker_user_id, export_sequence DESC);
