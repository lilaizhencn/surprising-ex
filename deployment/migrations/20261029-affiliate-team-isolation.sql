-- Stop all Gateway writers before applying. The confirmed checkpoint never crosses unresolved fees.
-- The last automatic cycle's fee cutoff allows late projections to catch up without early-paying a new cycle.
ALTER TABLE affiliate_profiles ADD COLUMN IF NOT EXISTS settlement_cutoff_at TIMESTAMPTZ;
ALTER TABLE affiliate_source_progress ADD COLUMN IF NOT EXISTS read_cluster_position BIGINT;
ALTER TABLE affiliate_source_progress ADD COLUMN IF NOT EXISTS read_order_id BIGINT;
UPDATE affiliate_source_progress SET read_cluster_position=cluster_position,read_order_id=order_id WHERE read_cluster_position IS NULL;
ALTER TABLE affiliate_source_progress ALTER COLUMN read_cluster_position SET DEFAULT 0;
ALTER TABLE affiliate_source_progress ALTER COLUMN read_cluster_position SET NOT NULL;
ALTER TABLE affiliate_source_progress ALTER COLUMN read_order_id SET DEFAULT 0;
ALTER TABLE affiliate_source_progress ALTER COLUMN read_order_id SET NOT NULL;

CREATE TABLE IF NOT EXISTS affiliate_deferred_fees (
 product_line VARCHAR(32) NOT NULL,cluster_position BIGINT NOT NULL,order_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,asset VARCHAR(20) NOT NULL,fee_units BIGINT NOT NULL CHECK(fee_units>0),occurred_at TIMESTAMPTZ NOT NULL,
 previous_position BIGINT NOT NULL,previous_order_id BIGINT NOT NULL,
 error VARCHAR(500) NOT NULL,attempt_count INTEGER NOT NULL DEFAULT 1 CHECK(attempt_count>0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),last_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 next_attempt_at TIMESTAMPTZ NOT NULL,resolved_at TIMESTAMPTZ,receipt_id BIGINT UNIQUE REFERENCES affiliate_fee_receipts(receipt_id),
 PRIMARY KEY(product_line,cluster_position,order_id),
 FOREIGN KEY(product_line,order_id,cluster_position) REFERENCES core_trade_fee_facts(product_line,order_id,cluster_position),
 CHECK((resolved_at IS NULL)=(receipt_id IS NULL)),
 CHECK((previous_position,previous_order_id)<(cluster_position,order_id))
);
CREATE INDEX IF NOT EXISTS affiliate_deferred_ready ON affiliate_deferred_fees(product_line,next_attempt_at,cluster_position,order_id) WHERE resolved_at IS NULL;
CREATE INDEX IF NOT EXISTS affiliate_deferred_checkpoint ON affiliate_deferred_fees(product_line,cluster_position,order_id) INCLUDE(previous_position,previous_order_id,occurred_at) WHERE resolved_at IS NULL;

CREATE OR REPLACE FUNCTION affiliate_deferred_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'deferred affiliate fee cannot be deleted'; END IF;
 IF (OLD.product_line,OLD.cluster_position,OLD.order_id,OLD.user_id,OLD.asset,OLD.fee_units,OLD.occurred_at,OLD.previous_position,OLD.previous_order_id)
 IS DISTINCT FROM (NEW.product_line,NEW.cluster_position,NEW.order_id,NEW.user_id,NEW.asset,NEW.fee_units,NEW.occurred_at,NEW.previous_position,NEW.previous_order_id)
 THEN RAISE EXCEPTION 'deferred affiliate fee identity is immutable'; END IF;
 IF OLD.resolved_at IS NOT NULL AND (OLD.resolved_at,OLD.receipt_id) IS DISTINCT FROM (NEW.resolved_at,NEW.receipt_id)
 THEN RAISE EXCEPTION 'resolved affiliate fee cannot be reopened'; END IF;
 IF NEW.resolved_at IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM affiliate_fee_receipts f WHERE f.receipt_id=NEW.receipt_id AND
   (f.product_line,f.cluster_position,f.order_id,f.source_user_id,f.asset,f.fee_units,f.occurred_at)=
   (NEW.product_line,NEW.cluster_position,NEW.order_id,NEW.user_id,NEW.asset,NEW.fee_units,NEW.occurred_at))
 THEN RAISE EXCEPTION 'deferred affiliate fee needs an identical committed receipt'; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS affiliate_deferred_immutable ON affiliate_deferred_fees;
CREATE TRIGGER affiliate_deferred_immutable BEFORE UPDATE OR DELETE ON affiliate_deferred_fees FOR EACH ROW EXECUTE FUNCTION affiliate_deferred_immutable();
