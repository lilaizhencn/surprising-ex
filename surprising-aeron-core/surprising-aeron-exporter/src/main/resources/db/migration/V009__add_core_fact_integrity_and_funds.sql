ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS before_business_state_hash BIGINT NOT NULL DEFAULT 0;
ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS before_funds_state_hash BIGINT NOT NULL DEFAULT 0;
ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS funds_state_hash BIGINT NOT NULL DEFAULT 0;
ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS matcher_sequence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS matcher_prefix_before BIGINT NOT NULL DEFAULT 0;
ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS matcher_prefix_after BIGINT NOT NULL DEFAULT 0;
ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS cluster_position BIGINT NOT NULL DEFAULT 0;
ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS integrity_key_id VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS integrity_key_fingerprint VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS integrity_payload_hash BYTEA NOT NULL DEFAULT CAST('' AS BYTEA);
ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS integrity_signature BYTEA NOT NULL DEFAULT CAST('' AS BYTEA);

ALTER TABLE core_event_projection
    ALTER COLUMN before_business_state_hash DROP DEFAULT;
ALTER TABLE core_event_projection
    ALTER COLUMN before_funds_state_hash DROP DEFAULT;
ALTER TABLE core_event_projection
    ALTER COLUMN funds_state_hash DROP DEFAULT;
ALTER TABLE core_event_projection
    ALTER COLUMN matcher_sequence DROP DEFAULT;
ALTER TABLE core_event_projection
    ALTER COLUMN matcher_prefix_before DROP DEFAULT;
ALTER TABLE core_event_projection
    ALTER COLUMN matcher_prefix_after DROP DEFAULT;
ALTER TABLE core_event_projection
    ALTER COLUMN cluster_position DROP DEFAULT;
ALTER TABLE core_event_projection
    ALTER COLUMN integrity_key_id DROP DEFAULT;
ALTER TABLE core_event_projection
    ALTER COLUMN integrity_key_fingerprint DROP DEFAULT;
ALTER TABLE core_event_projection
    ALTER COLUMN integrity_payload_hash DROP DEFAULT;
ALTER TABLE core_event_projection
    ALTER COLUMN integrity_signature DROP DEFAULT;

CREATE TABLE IF NOT EXISTS core_funds_posting_projection (
    product_line VARCHAR(32) NOT NULL,
    export_sequence BIGINT NOT NULL,
    posting_index INTEGER NOT NULL,
    asset VARCHAR(20) NOT NULL,
    owner_kind VARCHAR(16) NOT NULL,
    owner_id BIGINT NOT NULL,
    subledger VARCHAR(32) NOT NULL,
    units BIGINT NOT NULL,
    PRIMARY KEY (product_line, export_sequence, posting_index)
);

CREATE INDEX IF NOT EXISTS idx_core_funds_posting_owner
    ON core_funds_posting_projection (product_line, owner_kind, owner_id, asset, export_sequence);

ALTER TABLE core_treasury_projection
    ADD COLUMN IF NOT EXISTS liquidation_fee_units BIGINT NOT NULL DEFAULT 0;
ALTER TABLE core_treasury_projection
    ADD COLUMN IF NOT EXISTS funding_residual_units BIGINT NOT NULL DEFAULT 0;
ALTER TABLE core_treasury_projection
    ADD COLUMN IF NOT EXISTS rounding_residual_units BIGINT NOT NULL DEFAULT 0;
ALTER TABLE core_treasury_projection
    ADD COLUMN IF NOT EXISTS clearing_pnl_units BIGINT NOT NULL DEFAULT 0;

ALTER TABLE core_treasury_projection
    ALTER COLUMN liquidation_fee_units DROP DEFAULT;
ALTER TABLE core_treasury_projection
    ALTER COLUMN funding_residual_units DROP DEFAULT;
ALTER TABLE core_treasury_projection
    ALTER COLUMN rounding_residual_units DROP DEFAULT;
ALTER TABLE core_treasury_projection
    ALTER COLUMN clearing_pnl_units DROP DEFAULT;
