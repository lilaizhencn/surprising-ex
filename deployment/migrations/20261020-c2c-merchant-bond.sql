-- 商户保证金与广告托管分开追踪，实际资产仍在 SPOT Core 的固定内部托管账户。
CREATE TABLE IF NOT EXISTS c2c_merchant_bond_policy (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
    asset VARCHAR(20) NOT NULL REFERENCES assets(asset),
    amount_units BIGINT NOT NULL CHECK(amount_units>0),
    revision BIGINT NOT NULL DEFAULT 1 CHECK(revision>0),
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS c2c_merchant_bonds (
    bond_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    request_id UUID NOT NULL,
    asset VARCHAR(20) NOT NULL REFERENCES assets(asset),
    amount_units BIGINT NOT NULL CHECK(amount_units>0),
    status VARCHAR(24) NOT NULL CHECK(status IN ('RESERVING','LOCKED','REFUND_PENDING','RETURNED','FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id,request_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS c2c_merchant_bond_active ON c2c_merchant_bonds(user_id)
    WHERE status NOT IN ('RETURNED','FAILED');
CREATE INDEX IF NOT EXISTS c2c_merchant_bond_progress ON c2c_merchant_bonds(updated_at,bond_id)
    WHERE status IN ('RESERVING','REFUND_PENDING');
DO $bond_constraints$
DECLARE item RECORD;
BEGIN
    FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='c2c_fund_intents'::regclass AND contype='c'
        AND (pg_get_constraintdef(oid) LIKE '%owner_type%' OR pg_get_constraintdef(oid) LIKE '%purpose%')
    LOOP EXECUTE format('ALTER TABLE c2c_fund_intents DROP CONSTRAINT %I',item.conname); END LOOP;
END;
$bond_constraints$;
ALTER TABLE c2c_fund_intents ADD CONSTRAINT c2c_funds_business_purpose CHECK(
    (owner_type='AD' AND purpose IN ('AD_RESERVE','AD_REFUND')) OR
    (owner_type='ORDER' AND purpose IN ('ORDER_RESERVE','ORDER_REFUND','ORDER_RELEASE','BUYER_FEE')) OR
    (owner_type='MERCHANT' AND purpose IN ('MERCHANT_BOND','MERCHANT_BOND_REFUND')));
ALTER TABLE c2c_merchants DROP CONSTRAINT IF EXISTS c2c_merchants_status_check;
ALTER TABLE c2c_merchants ADD CONSTRAINT c2c_merchants_status_check
    CHECK(status IN ('PENDING','APPROVED','REJECTED','SUSPENDED','WITHDRAWN'));
CREATE OR REPLACE FUNCTION c2c_protect_merchant_bond() RETURNS trigger LANGUAGE plpgsql AS $bond_guard$
BEGIN
    IF NEW.bond_id<>OLD.bond_id OR NEW.user_id<>OLD.user_id OR NEW.request_id<>OLD.request_id
        OR NEW.asset<>OLD.asset OR NEW.amount_units<>OLD.amount_units OR NEW.created_at<>OLD.created_at THEN
        RAISE EXCEPTION 'merchant bond identity and original funds cannot change' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$bond_guard$;
DROP TRIGGER IF EXISTS c2c_merchant_bond_guard ON c2c_merchant_bonds;
CREATE TRIGGER c2c_merchant_bond_guard BEFORE UPDATE ON c2c_merchant_bonds
    FOR EACH ROW EXECUTE FUNCTION c2c_protect_merchant_bond();
