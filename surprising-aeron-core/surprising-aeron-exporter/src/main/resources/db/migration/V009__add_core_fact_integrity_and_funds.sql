ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS before_business_state_hash BIGINT NOT NULL DEFAULT 0;
ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS before_funds_state_hash BIGINT NOT NULL DEFAULT 0;
ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS funds_state_hash BIGINT NOT NULL DEFAULT 0;
ALTER TABLE core_event_projection
    ADD COLUMN IF NOT EXISTS matcher_sequence_before BIGINT NOT NULL DEFAULT 0;
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
    ALTER COLUMN matcher_sequence_before DROP DEFAULT;
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

COMMENT ON COLUMN core_event_projection.before_business_state_hash
    IS '应用当前 Core Fact 前的确定性业务状态哈希。';
COMMENT ON COLUMN core_event_projection.before_funds_state_hash
    IS '应用当前 Core Fact 前的确定性资金状态哈希。';
COMMENT ON COLUMN core_event_projection.funds_state_hash
    IS '应用当前 Core Fact 后的确定性资金状态哈希。';
COMMENT ON COLUMN core_event_projection.matcher_sequence_before
    IS '当前 Core Fact 覆盖的撮合结果区间起始序列；等于上一条事实的 matcher_sequence。';
COMMENT ON COLUMN core_event_projection.matcher_sequence
    IS '当前 Core Fact 应用后的撮合结果累计序列。';
COMMENT ON COLUMN core_event_projection.matcher_prefix_before
    IS '当前 Core Fact 覆盖撮合结果前的不可变前缀摘要。';
COMMENT ON COLUMN core_event_projection.matcher_prefix_after
    IS '当前 Core Fact 覆盖撮合结果后的不可变前缀摘要。';
COMMENT ON COLUMN core_event_projection.integrity_key_id
    IS '签署 Core Fact 的完整性密钥稳定标识。';
COMMENT ON COLUMN core_event_projection.integrity_key_fingerprint
    IS '签署 Core Fact 的公钥 SHA-256 指纹。';
COMMENT ON COLUMN core_event_projection.integrity_payload_hash
    IS 'Core Fact 规范化签名载荷的 SHA-256 摘要。';
COMMENT ON COLUMN core_event_projection.integrity_signature
    IS 'Core Fact 规范化载荷的 Ed25519 签名。';

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

COMMENT ON COLUMN core_funds_posting_projection.posting_index
    IS '同一 Core Fact 内资金分录的零基连续索引。';
COMMENT ON COLUMN core_funds_posting_projection.owner_kind
    IS '资金分录所有者类型：用户或 Treasury。';
COMMENT ON COLUMN core_funds_posting_projection.subledger
    IS '资金分录所属的可用、冻结、费用、保险等子账本。';
COMMENT ON COLUMN core_funds_posting_projection.units
    IS '资金分录的有符号最小精度整数金额。';

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
