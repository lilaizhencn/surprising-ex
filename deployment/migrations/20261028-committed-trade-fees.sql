-- Generic committed fee history; no Affiliate or online Core dependency.
CREATE TABLE IF NOT EXISTS core_trade_fee_progress (
    product_line VARCHAR(32) NOT NULL,
    order_id BIGINT NOT NULL,
    cluster_position BIGINT NOT NULL,
    cumulative_fee_units BIGINT NOT NULL,
    PRIMARY KEY(product_line,order_id)
);
CREATE TABLE IF NOT EXISTS core_trade_fee_facts (
    source_id BIGSERIAL PRIMARY KEY,
    product_line VARCHAR(32) NOT NULL,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    instrument_id VARCHAR(64) NOT NULL,
    asset VARCHAR(20) NOT NULL,
    fee_units BIGINT NOT NULL,
    cumulative_fee_units BIGINT NOT NULL,
    cluster_position BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE(product_line,order_id,cluster_position)
);
CREATE INDEX IF NOT EXISTS core_trade_fee_facts_source ON core_trade_fee_facts(product_line,cluster_position,order_id);
