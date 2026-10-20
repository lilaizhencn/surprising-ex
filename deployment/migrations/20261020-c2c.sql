-- C2C 业务只在 Gateway 编排；Core 继续持有真实资产。
-- 先部署迁移，随后发布应用；不修改任何现有账户余额或 Core 数据。
CREATE TABLE IF NOT EXISTS c2c_fund_intents (
    intent_id UUID PRIMARY KEY,
    transfer_id BIGINT NOT NULL UNIQUE CHECK (transfer_id > 0),
    owner_type VARCHAR(16) NOT NULL CHECK (owner_type IN ('AD','ORDER')),
    owner_id UUID NOT NULL,
    purpose VARCHAR(24) NOT NULL CHECK (purpose IN ('AD_RESERVE','ORDER_RESERVE','AD_REFUND','ORDER_REFUND','ORDER_RELEASE','BUYER_FEE')),
    source_user_id BIGINT NOT NULL CHECK (source_user_id > 0),
    target_user_id BIGINT NOT NULL CHECK (target_user_id > 0 AND target_user_id <> source_user_id),
    asset VARCHAR(20) NOT NULL CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    amount_units BIGINT NOT NULL CHECK (amount_units > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'PREPARED' CHECK (status IN ('PREPARED','DEBITED','CREDITED','APPLIED','REJECTED')),
    error_code VARCHAR(160),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    retry_after TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_type, owner_id, purpose),
    CHECK((owner_type='AD' AND purpose IN ('AD_RESERVE','AD_REFUND')) OR
          (owner_type='ORDER' AND purpose IN ('ORDER_RESERVE','ORDER_REFUND','ORDER_RELEASE','BUYER_FEE')))
);
CREATE INDEX IF NOT EXISTS c2c_fund_intents_pending ON c2c_fund_intents(retry_after, created_at)
    WHERE status NOT IN ('APPLIED','REJECTED');
COMMENT ON TABLE c2c_fund_intents IS 'C2C 不可变资金意图与续办进度；实际资金仍由 SPOT Core 记账，不是 SQL 钱包余额';

-- C2C 市场及订单；内部资金账户只保留序列 ID，不创建可登录用户。
CREATE TABLE IF NOT EXISTS c2c_configuration (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    max_active_ads INTEGER NOT NULL DEFAULT 20 CHECK(max_active_ads BETWEEN 1 AND 100),
    escrow_user_id BIGINT NOT NULL UNIQUE CHECK (escrow_user_id>0),
    fee_user_id BIGINT NOT NULL UNIQUE CHECK (fee_user_id>0 AND fee_user_id<>escrow_user_id),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision>0),
    updated_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
DO $c2c_accounts$
DECLARE escrow_id BIGINT; fee_id BIGINT;
BEGIN
    IF NOT EXISTS(SELECT 1 FROM c2c_configuration) THEN
        LOOP
            escrow_id := nextval(pg_get_serial_sequence('gateway_users','user_id'));
            EXIT WHEN NOT EXISTS(SELECT 1 FROM gateway_users WHERE user_id=escrow_id);
        END LOOP;
        LOOP
            fee_id := nextval(pg_get_serial_sequence('gateway_users','user_id'));
            EXIT WHEN fee_id<>escrow_id AND NOT EXISTS(SELECT 1 FROM gateway_users WHERE user_id=fee_id);
        END LOOP;
        INSERT INTO c2c_configuration(singleton,escrow_user_id,fee_user_id) VALUES(TRUE,escrow_id,fee_id);
    END IF;
END;
$c2c_accounts$;

-- 内部 Core 账户永远不能成为可登录用户，防止导入/手工指定 ID 绕过隔离。
CREATE OR REPLACE FUNCTION c2c_protect_internal_user() RETURNS trigger
LANGUAGE plpgsql SET search_path FROM CURRENT AS $c2c_guard$
BEGIN
    IF EXISTS(SELECT 1 FROM c2c_configuration WHERE NEW.user_id IN (escrow_user_id,fee_user_id)) THEN
        RAISE EXCEPTION 'C2C internal account cannot be a login user' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$c2c_guard$;
DROP TRIGGER IF EXISTS c2c_internal_user_guard ON gateway_users;
CREATE TRIGGER c2c_internal_user_guard BEFORE INSERT OR UPDATE OF user_id ON gateway_users
    FOR EACH ROW EXECUTE FUNCTION c2c_protect_internal_user();

CREATE OR REPLACE FUNCTION c2c_protect_fund_account_ids() RETURNS trigger
LANGUAGE plpgsql AS $c2c_config_guard$
BEGIN
    IF NEW.escrow_user_id<>OLD.escrow_user_id OR NEW.fee_user_id<>OLD.fee_user_id THEN
        RAISE EXCEPTION 'C2C fund account identities cannot change' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$c2c_config_guard$;
DROP TRIGGER IF EXISTS c2c_fund_account_guard ON c2c_configuration;
CREATE TRIGGER c2c_fund_account_guard BEFORE UPDATE ON c2c_configuration
    FOR EACH ROW EXECUTE FUNCTION c2c_protect_fund_account_ids();

CREATE TABLE IF NOT EXISTS c2c_markets (
    market_id UUID PRIMARY KEY,
    asset VARCHAR(20) NOT NULL REFERENCES assets(asset),
    fiat VARCHAR(8) NOT NULL CHECK (fiat ~ '^[A-Z]{3,8}$'),
    fiat_decimals INTEGER NOT NULL CHECK (fiat_decimals BETWEEN 0 AND 8),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    min_fiat NUMERIC(38,8) NOT NULL CHECK (min_fiat>0),
    max_fiat NUMERIC(38,8) NOT NULL CHECK (max_fiat>=min_fiat),
    min_price NUMERIC(38,18) NOT NULL CHECK (min_price>0),
    max_price NUMERIC(38,18) NOT NULL CHECK (max_price>=min_price),
    buyer_fee_ppm INTEGER NOT NULL DEFAULT 0 CHECK (buyer_fee_ppm BETWEEN 0 AND 999999),
    payment_window_seconds INTEGER NOT NULL DEFAULT 900 CHECK (payment_window_seconds BETWEEN 60 AND 3600),
    max_open_orders INTEGER NOT NULL DEFAULT 3 CHECK (max_open_orders BETWEEN 1 AND 20),
    require_kyc BOOLEAN NOT NULL DEFAULT TRUE,
    vip_min_completed INTEGER NOT NULL DEFAULT 100 CHECK (vip_min_completed>=0),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(asset,fiat)
);

CREATE TABLE IF NOT EXISTS c2c_payment_methods (
    code VARCHAR(32) PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    label VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    currencies TEXT[] NOT NULL DEFAULT '{}',
    requires_bank_name BOOLEAN NOT NULL DEFAULT FALSE,
    supports_qr BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision>0)
);
INSERT INTO c2c_payment_methods(code,label,requires_bank_name,supports_qr)
    VALUES ('BANK_TRANSFER','银行转账',TRUE,FALSE),('ALIPAY','支付宝',FALSE,TRUE),('WECHAT','微信支付',FALSE,TRUE)
    ON CONFLICT(code) DO NOTHING;

CREATE TABLE IF NOT EXISTS c2c_profiles (
    user_id BIGINT PRIMARY KEY REFERENCES gateway_users(user_id),
    nickname VARCHAR(40) NOT NULL,
    receive_strangers BOOLEAN NOT NULL DEFAULT FALSE,
    verification_minutes INTEGER NOT NULL DEFAULT 0 CHECK (verification_minutes IN (0,5,15,30)),
    revision BIGINT NOT NULL DEFAULT 1 CHECK(revision>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS c2c_profiles_nickname_unique ON c2c_profiles(lower(nickname));

CREATE TABLE IF NOT EXISTS c2c_merchants (
    user_id BIGINT PRIMARY KEY REFERENCES gateway_users(user_id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','SUSPENDED')),
    tier VARCHAR(16) NOT NULL DEFAULT 'VERIFIED' CHECK (tier IN ('VERIFIED','VIP')),
    application_text VARCHAR(2000) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK(revision>0),
    review_reason VARCHAR(1000),
    reviewed_by BIGINT REFERENCES gateway_users(user_id),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS c2c_payment_accounts (
    account_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    fiat VARCHAR(8) NOT NULL,
    method_code VARCHAR(32) NOT NULL REFERENCES c2c_payment_methods(code),
    details_ciphertext TEXT NOT NULL,
    masked_label VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','DISABLED')),
    review_reason VARCHAR(1000),
    reviewed_by BIGINT REFERENCES gateway_users(user_id),
    reviewed_at TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS c2c_payment_accounts_owner ON c2c_payment_accounts(user_id,created_at DESC);

CREATE TABLE IF NOT EXISTS c2c_ads (
    ad_id UUID PRIMARY KEY,
    maker_user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    market_id UUID NOT NULL REFERENCES c2c_markets(market_id),
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    price NUMERIC(38,18) NOT NULL CHECK (price>0),
    total_units BIGINT NOT NULL CHECK (total_units>0),
    available_units BIGINT NOT NULL CHECK (available_units>=0),
    reserved_units BIGINT NOT NULL DEFAULT 0 CHECK (reserved_units>=0),
    completed_units BIGINT NOT NULL DEFAULT 0 CHECK (completed_units>=0),
    closed_units BIGINT NOT NULL DEFAULT 0 CHECK (closed_units>=0),
    min_fiat NUMERIC(38,8) NOT NULL CHECK (min_fiat>0),
    max_fiat NUMERIC(38,8) NOT NULL CHECK (max_fiat>=min_fiat),
    method_codes TEXT[] NOT NULL,
    payment_account_ids UUID[] NOT NULL DEFAULT '{}',
    payment_window_seconds INTEGER NOT NULL CHECK (payment_window_seconds BETWEEN 60 AND 3600),
    terms VARCHAR(2000) NOT NULL DEFAULT '',
    min_counterparty_completed INTEGER NOT NULL DEFAULT 0 CHECK (min_counterparty_completed>=0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVING','ACTIVE','PAUSED','CLOSING','CLOSED','REJECTED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (available_units::numeric+reserved_units+completed_units+closed_units=total_units::numeric)
);
CREATE INDEX IF NOT EXISTS c2c_ads_market ON c2c_ads(market_id,side,price,ad_id) WHERE status='ACTIVE';

CREATE TABLE IF NOT EXISTS c2c_quotes (
    quote_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    ad_id UUID NOT NULL REFERENCES c2c_ads(ad_id),
    ad_revision BIGINT NOT NULL,
    market_revision BIGINT NOT NULL,
    quantity_units BIGINT NOT NULL CHECK (quantity_units>0),
    buyer_fee_units BIGINT NOT NULL CHECK (buyer_fee_units>=0 AND buyer_fee_units<quantity_units),
    fiat_amount NUMERIC(38,8) NOT NULL CHECK (fiat_amount>0),
    price NUMERIC(38,18) NOT NULL CHECK (price>0),
    payment_account_id UUID NOT NULL REFERENCES c2c_payment_accounts(account_id),
    payment_account_revision BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS c2c_orders (
    order_id UUID PRIMARY KEY,
    quote_id UUID NOT NULL UNIQUE REFERENCES c2c_quotes(quote_id),
    ad_id UUID NOT NULL REFERENCES c2c_ads(ad_id),
    market_id UUID NOT NULL REFERENCES c2c_markets(market_id),
    creator_user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    buyer_user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    seller_user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    quantity_units BIGINT NOT NULL CHECK (quantity_units>0),
    buyer_fee_units BIGINT NOT NULL CHECK (buyer_fee_units>=0 AND buyer_fee_units<quantity_units),
    fiat_amount NUMERIC(38,8) NOT NULL CHECK (fiat_amount>0),
    price NUMERIC(38,18) NOT NULL CHECK (price>0),
    payment_account_id UUID NOT NULL,
    payment_details_ciphertext TEXT NOT NULL,
    payment_window_seconds INTEGER NOT NULL CHECK (payment_window_seconds BETWEEN 60 AND 3600),
    terms VARCHAR(2000) NOT NULL DEFAULT '',
    status VARCHAR(24) NOT NULL CHECK (status IN ('RESERVING','PAYMENT_PENDING','PAID','APPEALED','RELEASE_PENDING','REFUND_PENDING','COMPLETED','CANCELED','EXPIRED','FAILED')),
    requested_terminal VARCHAR(16) CHECK (requested_terminal IN ('CANCELED','EXPIRED','FAILED')),
    payment_expires_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    canceled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision>0),
    CHECK(buyer_user_id<>seller_user_id)
);
CREATE INDEX IF NOT EXISTS c2c_orders_buyer ON c2c_orders(buyer_user_id,created_at DESC,order_id);
CREATE INDEX IF NOT EXISTS c2c_orders_seller ON c2c_orders(seller_user_id,created_at DESC,order_id);
CREATE INDEX IF NOT EXISTS c2c_orders_expiry ON c2c_orders(payment_expires_at) WHERE status='PAYMENT_PENDING';

CREATE TABLE IF NOT EXISTS c2c_appeals (
    appeal_id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES c2c_orders(order_id),
    opener_user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    previous_order_status VARCHAR(24) NOT NULL CHECK(previous_order_status IN ('PAYMENT_PENDING','PAID')),
    reason VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','REVIEWING','RESOLVED','WITHDRAWN')),
    decision VARCHAR(8) CHECK (decision IN ('RELEASE','REFUND')),
    decision_reason VARCHAR(2000),
    decided_by BIGINT REFERENCES gateway_users(user_id),
    approval_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS c2c_appeals_open_order ON c2c_appeals(order_id) WHERE status IN ('OPEN','REVIEWING');

CREATE TABLE IF NOT EXISTS c2c_order_events (
    event_id BIGSERIAL PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES c2c_orders(order_id),
    actor_user_id BIGINT,
    event_type VARCHAR(40) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS c2c_order_events_order ON c2c_order_events(order_id,event_id);

CREATE TABLE IF NOT EXISTS c2c_security_grants (
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(user_id,token_hash)
);
CREATE TABLE IF NOT EXISTS c2c_quick_replies (
    reply_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    text VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS c2c_relations (
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    target_user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('FOLLOW','BLOCK')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id,target_user_id,kind),
    CHECK(user_id<>target_user_id)
);
CREATE TABLE IF NOT EXISTS c2c_reports (
    report_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    target_user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    order_id UUID REFERENCES c2c_orders(order_id),
    reason VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','REVIEWED','DISMISSED')),
    review_reason VARCHAR(2000),
    reviewed_by BIGINT REFERENCES gateway_users(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    CHECK(user_id<>target_user_id)
);
CREATE TABLE IF NOT EXISTS c2c_conversations (
    conversation_id UUID PRIMARY KEY,
    low_user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    high_user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    order_id UUID REFERENCES c2c_orders(order_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK(low_user_id<high_user_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS c2c_conversation_direct ON c2c_conversations(low_user_id,high_user_id) WHERE order_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS c2c_conversation_order ON c2c_conversations(order_id) WHERE order_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS c2c_attachments (
    attachment_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    order_id UUID REFERENCES c2c_orders(order_id),
    purpose VARCHAR(16) NOT NULL CHECK(purpose IN ('PAYMENT_QR','PROOF','MESSAGE')),
    storage_key TEXT NOT NULL UNIQUE,
    content_type VARCHAR(80) NOT NULL,
    byte_count INTEGER NOT NULL CHECK(byte_count>0 AND byte_count<=10485760),
    sha256 VARCHAR(64) NOT NULL,
    original_name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS c2c_messages (
    message_id BIGSERIAL PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES c2c_conversations(conversation_id),
    sender_user_id BIGINT REFERENCES gateway_users(user_id),
    request_id UUID NOT NULL,
    text VARCHAR(4000) NOT NULL DEFAULT '',
    attachment_id UUID REFERENCES c2c_attachments(attachment_id),
    system_type VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(conversation_id,request_id),
    CHECK(length(text)>0 OR attachment_id IS NOT NULL OR system_type IS NOT NULL),
    CHECK((sender_user_id IS NULL)=(system_type IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS c2c_messages_conversation ON c2c_messages(conversation_id,message_id);
CREATE TABLE IF NOT EXISTS c2c_message_reads (
    conversation_id UUID NOT NULL REFERENCES c2c_conversations(conversation_id),
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    last_message_id BIGINT NOT NULL DEFAULT 0 CHECK(last_message_id>=0),
    PRIMARY KEY(conversation_id,user_id)
);
CREATE TABLE IF NOT EXISTS c2c_price_alerts (
    alert_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    market_id UUID NOT NULL REFERENCES c2c_markets(market_id),
    side VARCHAR(4) NOT NULL CHECK(side IN ('BUY','SELL')),
    target_price NUMERIC(38,18) NOT NULL CHECK(target_price>0),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    triggered_at TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 1 CHECK(revision>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS c2c_notifications (
    notification_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    kind VARCHAR(40) NOT NULL,
    entity_id UUID NOT NULL,
    details JSONB NOT NULL DEFAULT '{}',
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS c2c_notifications_owner ON c2c_notifications(user_id,notification_id);

-- 先持久接收新建请求，再在业务事务内锁定与完成，支持丢回包后的只读恢复及明确停止。
CREATE TABLE IF NOT EXISTS c2c_submissions (
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    request_key VARCHAR(80) NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK(kind IN ('AD_PUBLISH','ORDER_CREATE')),
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING' CHECK(status IN ('PROCESSING','SUCCEEDED','REJECTED')),
    result_id UUID,
    error_code VARCHAR(80),
    error_message VARCHAR(240),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id,request_key),
    CHECK((status='SUCCEEDED')=(result_id IS NOT NULL)),
    CHECK((status='REJECTED')=(error_code IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS c2c_submissions_unfinished ON c2c_submissions(created_at) WHERE status='PROCESSING';

-- 待续办索引只覆盖非终态，避免历史记录挤占续办批量。
CREATE INDEX IF NOT EXISTS c2c_ads_funding_pending ON c2c_ads(updated_at,ad_id) WHERE status IN ('RESERVING','CLOSING');
CREATE INDEX IF NOT EXISTS c2c_orders_funding_pending ON c2c_orders(updated_at,order_id) WHERE status IN ('RESERVING','RELEASE_PENDING','REFUND_PENDING');
CREATE INDEX IF NOT EXISTS c2c_quotes_expiry ON c2c_quotes(expires_at);
CREATE INDEX IF NOT EXISTS c2c_grants_expiry ON c2c_security_grants(expires_at);

INSERT INTO gateway_permissions(permission_code,permission_name,description) VALUES
    ('admin.c2c.read','读取 C2C','读取市场、商家、广告、订单和资金意图。'),
    ('admin.c2c.write','管理 C2C','配置市场与收款方式、审核商家和收款账户、暂停广告。'),
    ('admin.c2c.resolve','裁决 C2C 申诉','双人审批后执行固定订单双方的放币或退款。'),
    ('admin.c2c.sensitive.read','读取 C2C 敏感资料','审计后读取实名收款资料和订单证据。')
ON CONFLICT(permission_code) DO UPDATE SET permission_name=EXCLUDED.permission_name,description=EXCLUDED.description;
INSERT INTO gateway_role_permissions(role_id,permission_id)
SELECT r.role_id,p.permission_id FROM gateway_roles r CROSS JOIN gateway_permissions p
WHERE (r.role_code='ADMIN' AND p.permission_code IN ('admin.c2c.read','admin.c2c.write','admin.c2c.resolve','admin.c2c.sensitive.read'))
   OR (r.role_code='SUPPORT' AND p.permission_code='admin.c2c.read')
ON CONFLICT DO NOTHING;

CREATE INDEX IF NOT EXISTS c2c_relations_owner ON c2c_relations(user_id,kind,created_at DESC,target_user_id DESC);
CREATE INDEX IF NOT EXISTS c2c_reports_owner ON c2c_reports(user_id,created_at DESC,report_id DESC);
CREATE INDEX IF NOT EXISTS c2c_alerts_pending ON c2c_price_alerts(created_at,alert_id) WHERE enabled;

-- 续办只允许推进原意图，不能借 UPDATE 改金额、身份或交易对手。
CREATE OR REPLACE FUNCTION c2c_protect_fund_intent() RETURNS trigger
LANGUAGE plpgsql AS $c2c_intent_guard$
BEGIN
    IF ROW(NEW.intent_id,NEW.transfer_id,NEW.owner_type,NEW.owner_id,NEW.purpose,NEW.source_user_id,NEW.target_user_id,NEW.asset,NEW.amount_units,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.intent_id,OLD.transfer_id,OLD.owner_type,OLD.owner_id,OLD.purpose,OLD.source_user_id,OLD.target_user_id,OLD.asset,OLD.amount_units,OLD.created_at) THEN
        RAISE EXCEPTION 'C2C fund intent facts cannot change' USING ERRCODE='23514';
    END IF;
    IF NEW.status<>OLD.status AND NOT (
        (OLD.status='PREPARED' AND NEW.status IN ('DEBITED','REJECTED')) OR
        (OLD.status='DEBITED' AND NEW.status='CREDITED') OR
        (OLD.status='CREDITED' AND NEW.status='APPLIED')) THEN
        RAISE EXCEPTION 'C2C fund intent cannot skip or rewind settlement' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$c2c_intent_guard$;
DROP TRIGGER IF EXISTS c2c_fund_intent_guard ON c2c_fund_intents;
CREATE TRIGGER c2c_fund_intent_guard BEFORE UPDATE ON c2c_fund_intents FOR EACH ROW EXECUTE FUNCTION c2c_protect_fund_intent();
