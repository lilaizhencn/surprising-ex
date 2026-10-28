-- Gateway affiliate business state. Trading balances remain owned by Core.
CREATE TABLE IF NOT EXISTS affiliate_settings (
    version BIGSERIAL PRIMARY KEY,
    referral_rate_ppm INTEGER NOT NULL CHECK (referral_rate_ppm BETWEEN 0 AND 1000000),
    kol_rate_ppm INTEGER NOT NULL CHECK (kol_rate_ppm BETWEEN 0 AND 1000000),
    settlement_hours INTEGER NOT NULL CHECK (settlement_hours BETWEEN 1 AND 720),
    effective_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    actor_id BIGINT,
    reason TEXT NOT NULL
);
INSERT INTO affiliate_settings(referral_rate_ppm,kol_rate_ppm,settlement_hours,reason)
SELECT 200000,200000,24,'初始配置：普通邀请与 KOL 默认 20%，每日结算'
WHERE NOT EXISTS(SELECT 1 FROM affiliate_settings);

CREATE TABLE IF NOT EXISTS affiliate_profiles (
    user_id BIGINT PRIMARY KEY REFERENCES gateway_users(user_id),
    invite_code VARCHAR(32) NOT NULL UNIQUE,
    kol_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    kol_suspended BOOLEAN NOT NULL DEFAULT FALSE,
    auto_settlement BOOLEAN NOT NULL DEFAULT TRUE,
    rate_ppm INTEGER CHECK (rate_ppm BETWEEN 0 AND 1000000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS affiliate_profile_history (
    version BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES affiliate_profiles(user_id),
    kol_enabled BOOLEAN NOT NULL,
    auto_settlement BOOLEAN NOT NULL,
    rate_ppm INTEGER CHECK (rate_ppm BETWEEN 0 AND 1000000),
    effective_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    actor_id BIGINT NOT NULL,
    reason TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS affiliate_profile_history_time ON affiliate_profile_history(user_id,effective_at DESC,version DESC);
CREATE TABLE IF NOT EXISTS affiliate_referrals (
    user_id BIGINT PRIMARY KEY REFERENCES affiliate_profiles(user_id),
    inviter_id BIGINT NOT NULL REFERENCES affiliate_profiles(user_id),
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK(user_id <> inviter_id)
);
CREATE INDEX IF NOT EXISTS affiliate_referrals_inviter ON affiliate_referrals(inviter_id,user_id);
CREATE TABLE IF NOT EXISTS affiliate_team_terms (
    term_id BIGSERIAL PRIMARY KEY,
    child_id BIGINT NOT NULL REFERENCES affiliate_profiles(user_id),
    parent_id BIGINT NOT NULL REFERENCES affiliate_profiles(user_id),
    share_ppm INTEGER NOT NULL CHECK(share_ppm BETWEEN 0 AND 1000000),
    effective_from TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    effective_until TIMESTAMPTZ,
    actor_id BIGINT NOT NULL,
    CHECK(child_id <> parent_id),
    CHECK(effective_until IS NULL OR effective_until >= effective_from)
);
CREATE UNIQUE INDEX IF NOT EXISTS affiliate_team_current ON affiliate_team_terms(child_id) WHERE effective_until IS NULL;
CREATE INDEX IF NOT EXISTS affiliate_team_parent ON affiliate_team_terms(parent_id,child_id);
CREATE TABLE IF NOT EXISTS affiliate_invitations (
    token VARCHAR(64) PRIMARY KEY,
    inviter_id BIGINT NOT NULL REFERENCES affiliate_profiles(user_id),
    target_id BIGINT REFERENCES gateway_users(user_id),
    share_ppm INTEGER NOT NULL CHECK(share_ppm BETWEEN 0 AND 1000000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_by BIGINT REFERENCES gateway_users(user_id),
    accepted_at TIMESTAMPTZ,
    CHECK(expires_at > created_at)
);
CREATE INDEX IF NOT EXISTS affiliate_invitations_target ON affiliate_invitations(target_id,created_at DESC);
CREATE TABLE IF NOT EXISTS affiliate_budget_accounts (
    product_line VARCHAR(32) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    actor_id BIGINT NOT NULL,
    reason TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS affiliate_fee_receipts (
    receipt_id BIGSERIAL PRIMARY KEY,
    product_line VARCHAR(32) NOT NULL,
    cluster_position BIGINT NOT NULL,
    source_user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    asset VARCHAR(20) NOT NULL,
    fee_units BIGINT NOT NULL CHECK(fee_units > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    inviter_id BIGINT,
    root_id BIGINT,
    settings_version BIGINT,
    pool_units BIGINT NOT NULL CHECK(pool_units >= 0),
    disposition VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(product_line,order_id,cluster_position)
);
CREATE TABLE IF NOT EXISTS affiliate_commissions (
    commission_id BIGSERIAL PRIMARY KEY,
    receipt_id BIGINT NOT NULL REFERENCES affiliate_fee_receipts(receipt_id),
    beneficiary_id BIGINT NOT NULL REFERENCES affiliate_profiles(user_id),
    received_units BIGINT NOT NULL CHECK(received_units >= 0),
    delegated_units BIGINT NOT NULL CHECK(delegated_units >= 0 AND delegated_units <= received_units),
    retained_units BIGINT NOT NULL CHECK(retained_units = received_units-delegated_units),
    term_id BIGINT REFERENCES affiliate_team_terms(term_id),
    share_ppm INTEGER NOT NULL CHECK(share_ppm BETWEEN 0 AND 1000000),
    payout_id UUID,
    UNIQUE(receipt_id,beneficiary_id)
);
CREATE INDEX IF NOT EXISTS affiliate_commissions_pending ON affiliate_commissions(beneficiary_id,commission_id) WHERE payout_id IS NULL;
CREATE TABLE IF NOT EXISTS affiliate_payouts (
    payout_id UUID PRIMARY KEY,
    beneficiary_id BIGINT NOT NULL REFERENCES affiliate_profiles(user_id),
    budget_user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    product_line VARCHAR(32) NOT NULL,
    asset VARCHAR(20) NOT NULL,
    amount_units BIGINT NOT NULL CHECK(amount_units > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','UNKNOWN','PAID','REJECTED')),
    transfer_id VARCHAR(32),
    replacement_id UUID UNIQUE REFERENCES affiliate_payouts(payout_id),
    error TEXT,
    claimed_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    paid_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS affiliate_payouts_work ON affiliate_payouts(status,last_attempt_at NULLS FIRST,created_at) WHERE status <> 'PAID';
CREATE TABLE IF NOT EXISTS affiliate_source_progress (
    product_line VARCHAR(32) PRIMARY KEY,
    cluster_position BIGINT NOT NULL DEFAULT 0,
    order_id BIGINT NOT NULL DEFAULT 0,
    checked_at TIMESTAMPTZ,
    error TEXT
);
CREATE TABLE IF NOT EXISTS affiliate_audit (
    audit_id BIGSERIAL PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    target_id BIGINT,
    detail TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO gateway_permissions(permission_code,permission_name,description) VALUES
('admin.affiliate.read','查看邀请返佣','查看 KOL、佣金及结算配置'),
('admin.affiliate.write','管理邀请返佣','开停 KOL、配置比例、预算及触发结算')
ON CONFLICT(permission_code) DO NOTHING;
INSERT INTO gateway_role_permissions(role_id,permission_id)
SELECT r.role_id,p.permission_id FROM gateway_roles r CROSS JOIN gateway_permissions p
WHERE r.role_code='ADMIN' AND p.permission_code IN ('admin.affiliate.read','admin.affiliate.write')
ON CONFLICT DO NOTHING;
ALTER TABLE affiliate_profiles ADD COLUMN IF NOT EXISTS next_settlement_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp()+interval '24 hours');
CREATE TABLE IF NOT EXISTS affiliate_accrual_buckets (
    product_line VARCHAR(32) NOT NULL,
    order_id BIGINT NOT NULL,
    root_id BIGINT NOT NULL,
    settings_version BIGINT NOT NULL,
    rate_ppm INTEGER NOT NULL,
    fee_units NUMERIC(38,0) NOT NULL,
    pool_units NUMERIC(38,0) NOT NULL,
    PRIMARY KEY(product_line,order_id,root_id,settings_version,rate_ppm)
);

CREATE INDEX IF NOT EXISTS affiliate_receipts_inviter ON affiliate_fee_receipts(inviter_id,source_user_id,receipt_id);
CREATE INDEX IF NOT EXISTS affiliate_commissions_beneficiary ON affiliate_commissions(beneficiary_id,receipt_id);
