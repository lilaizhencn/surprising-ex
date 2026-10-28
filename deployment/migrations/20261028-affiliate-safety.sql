-- Apply after 20261028-affiliate.sql. Canonical receipts remain the source of fee metadata.
CREATE INDEX IF NOT EXISTS affiliate_team_child_history ON affiliate_team_terms(child_id,effective_from DESC,term_id DESC) INCLUDE(parent_id,share_ppm,effective_until);
CREATE INDEX IF NOT EXISTS affiliate_team_parent_time ON affiliate_team_terms(parent_id,effective_from) INCLUDE(child_id,effective_until);
CREATE INDEX IF NOT EXISTS affiliate_commissions_payout ON affiliate_commissions(payout_id) WHERE payout_id IS NOT NULL;

-- Preserve historical incoming fractions; never rewrite historical money to repair old rounding.
CREATE TABLE IF NOT EXISTS affiliate_delegation_buckets (
 product_line VARCHAR(32) NOT NULL,order_id BIGINT NOT NULL,asset VARCHAR(20) NOT NULL,
 term_id BIGINT NOT NULL REFERENCES affiliate_team_terms(term_id),
 incoming_units NUMERIC(38,0) NOT NULL CHECK(incoming_units>=0),
 PRIMARY KEY(product_line,order_id,asset,term_id)
);
INSERT INTO affiliate_delegation_buckets(product_line,order_id,asset,term_id,incoming_units)
 SELECT f.product_line,f.order_id,f.asset,t.term_id,sum(p.received_units)
 FROM affiliate_commissions c JOIN affiliate_team_terms t ON t.term_id=c.term_id
 JOIN affiliate_commissions p ON p.receipt_id=c.receipt_id AND p.beneficiary_id=t.parent_id
 JOIN affiliate_fee_receipts f ON f.receipt_id=c.receipt_id
 GROUP BY f.product_line,f.order_id,f.asset,t.term_id ON CONFLICT DO NOTHING;
ALTER TABLE affiliate_payouts ADD COLUMN IF NOT EXISTS claim_token UUID;
ALTER TABLE affiliate_payouts ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE affiliate_payouts ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT '-infinity';
CREATE INDEX IF NOT EXISTS affiliate_payouts_ready ON affiliate_payouts(next_attempt_at,last_attempt_at NULLS FIRST,created_at) WHERE status IN('PENDING','UNKNOWN');

-- Immutable, FK-checked receipt metadata allows bounded pending-money index scans.
ALTER TABLE affiliate_commissions ADD COLUMN IF NOT EXISTS product_line VARCHAR(32);
ALTER TABLE affiliate_commissions ADD COLUMN IF NOT EXISTS asset VARCHAR(20);
ALTER TABLE affiliate_commissions ADD COLUMN IF NOT EXISTS occurred_at TIMESTAMPTZ;
UPDATE affiliate_commissions c SET product_line=f.product_line,asset=f.asset,occurred_at=f.occurred_at
 FROM affiliate_fee_receipts f WHERE c.receipt_id=f.receipt_id AND c.product_line IS NULL;
ALTER TABLE affiliate_commissions ALTER COLUMN product_line SET NOT NULL;
ALTER TABLE affiliate_commissions ALTER COLUMN asset SET NOT NULL;
ALTER TABLE affiliate_commissions ALTER COLUMN occurred_at SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS affiliate_receipt_metadata ON affiliate_fee_receipts(receipt_id,product_line,asset,occurred_at);
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='affiliate_commission_receipt_metadata' AND conrelid='affiliate_commissions'::regclass) THEN
  ALTER TABLE affiliate_commissions ADD CONSTRAINT affiliate_commission_receipt_metadata FOREIGN KEY(receipt_id,product_line,asset,occurred_at) REFERENCES affiliate_fee_receipts(receipt_id,product_line,asset,occurred_at);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='affiliate_commission_payout' AND conrelid='affiliate_commissions'::regclass) THEN
  ALTER TABLE affiliate_commissions ADD CONSTRAINT affiliate_commission_payout FOREIGN KEY(payout_id) REFERENCES affiliate_payouts(payout_id);
 END IF;
END $$;
CREATE INDEX IF NOT EXISTS affiliate_commissions_mature ON affiliate_commissions(beneficiary_id,product_line,asset,occurred_at,commission_id) INCLUDE(retained_units)
 WHERE payout_id IS NULL AND retained_units>0;

-- Rebuildable settlement work index. It contains no independent payable amount.
CREATE TABLE IF NOT EXISTS affiliate_settlement_groups (
 beneficiary_id BIGINT NOT NULL REFERENCES affiliate_profiles(user_id),product_line VARCHAR(32) NOT NULL,asset VARCHAR(20) NOT NULL,
 first_fee_at TIMESTAMPTZ NOT NULL,available_at TIMESTAMPTZ NOT NULL,blocked BOOLEAN NOT NULL DEFAULT FALSE,checked_at TIMESTAMPTZ,
 PRIMARY KEY(beneficiary_id,product_line,asset)
);
CREATE INDEX IF NOT EXISTS affiliate_settlement_groups_ready ON affiliate_settlement_groups(available_at,beneficiary_id,product_line,asset);
CREATE INDEX IF NOT EXISTS affiliate_settlement_groups_manual ON affiliate_settlement_groups(checked_at NULLS FIRST,beneficiary_id,product_line,asset);
INSERT INTO affiliate_settlement_groups(beneficiary_id,product_line,asset,first_fee_at,available_at)
 SELECT c.beneficiary_id,c.product_line,c.asset,min(c.occurred_at),
 CASE WHEN p.auto_settlement THEN GREATEST(p.next_settlement_at,min(c.occurred_at)+(s.settlement_hours*interval '1 hour')) ELSE 'infinity' END
 FROM affiliate_commissions c JOIN affiliate_profiles p ON p.user_id=c.beneficiary_id
 CROSS JOIN LATERAL(SELECT settlement_hours FROM affiliate_settings ORDER BY version DESC LIMIT 1)s
 WHERE c.payout_id IS NULL AND c.retained_units>0
 GROUP BY c.beneficiary_id,c.product_line,c.asset,p.auto_settlement,p.next_settlement_at,s.settlement_hours
 ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION affiliate_payout_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'affiliate payout cannot be deleted'; END IF;
 IF (OLD.beneficiary_id,OLD.budget_user_id,OLD.product_line,OLD.asset,OLD.amount_units)
   IS DISTINCT FROM (NEW.beneficiary_id,NEW.budget_user_id,NEW.product_line,NEW.asset,NEW.amount_units)
 THEN RAISE EXCEPTION 'committed affiliate payout intent is immutable'; END IF;
 IF OLD.status IN('PAID','REJECTED') AND NEW.status<>OLD.status THEN RAISE EXCEPTION 'affiliate payout terminal status is immutable'; END IF;
 IF OLD.replacement_id IS NOT NULL AND NEW.replacement_id IS DISTINCT FROM OLD.replacement_id THEN RAISE EXCEPTION 'affiliate payout replacement is immutable'; END IF;
 IF NEW.replacement_id=NEW.payout_id THEN RAISE EXCEPTION 'affiliate payout cannot replace itself'; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS affiliate_payout_immutable ON affiliate_payouts;
CREATE TRIGGER affiliate_payout_immutable BEFORE UPDATE OR DELETE ON affiliate_payouts FOR EACH ROW EXECUTE FUNCTION affiliate_payout_immutable();
CREATE OR REPLACE FUNCTION affiliate_commission_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'affiliate commission cannot be deleted'; END IF;
 IF TG_OP='UPDATE' AND (OLD.receipt_id,OLD.beneficiary_id,OLD.received_units,OLD.delegated_units,OLD.retained_units,OLD.term_id,OLD.share_ppm,OLD.product_line,OLD.asset,OLD.occurred_at)
 IS DISTINCT FROM (NEW.receipt_id,NEW.beneficiary_id,NEW.received_units,NEW.delegated_units,NEW.retained_units,NEW.term_id,NEW.share_ppm,NEW.product_line,NEW.asset,NEW.occurred_at)
 THEN RAISE EXCEPTION 'affiliate commission is immutable'; END IF;
 IF (TG_OP='INSERT' OR NEW.payout_id IS DISTINCT FROM OLD.payout_id) AND NEW.payout_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM affiliate_payouts WHERE payout_id=NEW.payout_id AND status='PENDING' AND claim_token IS NULL
    AND beneficiary_id=NEW.beneficiary_id AND product_line=NEW.product_line AND asset=NEW.asset)
 THEN RAISE EXCEPTION 'affiliate commission cannot attach to a claimed or mismatched payout'; END IF;
 IF TG_OP='UPDATE' AND OLD.payout_id IS NOT NULL AND NEW.payout_id IS DISTINCT FROM OLD.payout_id AND NOT EXISTS(
  SELECT 1 FROM affiliate_payouts WHERE payout_id=OLD.payout_id AND status='REJECTED' AND replacement_id=NEW.payout_id)
 THEN RAISE EXCEPTION 'affiliate commission cannot leave an unresolved or paid payout'; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS affiliate_commission_immutable ON affiliate_commissions;
CREATE TRIGGER affiliate_commission_immutable BEFORE INSERT OR UPDATE OR DELETE ON affiliate_commissions FOR EACH ROW EXECUTE FUNCTION affiliate_commission_immutable();

CREATE OR REPLACE FUNCTION affiliate_receipt_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 RAISE EXCEPTION 'affiliate fee receipt is immutable';
END $$;
DROP TRIGGER IF EXISTS affiliate_receipt_immutable ON affiliate_fee_receipts;
CREATE TRIGGER affiliate_receipt_immutable BEFORE UPDATE OR DELETE ON affiliate_fee_receipts FOR EACH ROW EXECUTE FUNCTION affiliate_receipt_immutable();
