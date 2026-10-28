-- Read-only diagnostics. Run whole-ledger checks on an isolated replica or in a maintenance window.
BEGIN READ ONLY;
SET LOCAL statement_timeout='30s';

-- Expected: no rows. Every fee pool equals the sum of retained units across all levels.
SELECT f.receipt_id,f.product_line,f.asset,f.pool_units,COALESCE(sum(c.retained_units),0) AS retained_units
FROM affiliate_fee_receipts f LEFT JOIN affiliate_commissions c USING(receipt_id)
GROUP BY f.receipt_id
HAVING f.pool_units<>COALESCE(sum(c.retained_units),0)
ORDER BY f.receipt_id LIMIT 1000;

-- Expected: no rows. Superseded rejected intents keep their history but no longer own payables.
SELECT p.payout_id,p.product_line,p.asset,p.status,p.amount_units,COALESCE(sum(c.retained_units),0) AS linked_units
FROM affiliate_payouts p LEFT JOIN affiliate_commissions c USING(payout_id)
WHERE p.replacement_id IS NULL
GROUP BY p.payout_id
HAVING p.amount_units<>COALESCE(sum(c.retained_units),0) OR count(c.commission_id)=0
 OR NOT COALESCE(bool_and(c.beneficiary_id=p.beneficiary_id AND c.product_line=p.product_line AND c.asset=p.asset),FALSE)
ORDER BY p.payout_id LIMIT 1000;

-- Per-product/asset payable partition; compare these categories with Core transfer identities.
SELECT c.product_line,c.asset,sum(c.retained_units) AS payable_units,
 COALESCE(sum(c.retained_units) FILTER(WHERE c.payout_id IS NULL),0) AS unsettled_units,
 COALESCE(sum(c.retained_units) FILTER(WHERE p.status IN('PENDING','UNKNOWN')),0) AS in_flight_units,
 COALESCE(sum(c.retained_units) FILTER(WHERE p.status='REJECTED'),0) AS rejected_units,
 COALESCE(sum(c.retained_units) FILTER(WHERE p.status='PAID'),0) AS paid_units
FROM affiliate_commissions c LEFT JOIN affiliate_payouts p USING(payout_id)
GROUP BY c.product_line,c.asset ORDER BY c.product_line,c.asset;

-- The next unconsumed fee identifies a blocked product without advancing its checkpoint.
WITH products(product_line) AS (VALUES('SPOT'),('LINEAR_PERPETUAL'),('INVERSE_PERPETUAL'),('LINEAR_DELIVERY'),('INVERSE_DELIVERY'),('OPTION'))
SELECT products.product_line,p.checked_at,p.error,p.cluster_position,p.order_id,
 next.cluster_position AS next_position,next.order_id AS next_order,next.occurred_at AS next_fee_at
FROM products LEFT JOIN affiliate_source_progress p USING(product_line)
LEFT JOIN LATERAL(SELECT cluster_position,order_id,occurred_at FROM core_trade_fee_facts f
 WHERE f.product_line=products.product_line AND (f.cluster_position,f.order_id)>(COALESCE(p.cluster_position,0),COALESCE(p.order_id,0)) AND f.fee_units>0
 ORDER BY f.cluster_position,f.order_id LIMIT 1) next ON TRUE
ORDER BY products.product_line;

-- Queue diagnosis; totals remain owned by the immutable commission ledger.
SELECT g.*,p.auto_settlement,p.next_settlement_at,b.user_id AS budget_user_id,
 sender.status AS budget_status,recipient.status AS recipient_status
FROM affiliate_settlement_groups g JOIN affiliate_profiles p ON p.user_id=g.beneficiary_id
JOIN gateway_users recipient ON recipient.user_id=g.beneficiary_id
LEFT JOIN affiliate_budget_accounts b USING(product_line)
LEFT JOIN gateway_users sender ON sender.user_id=b.user_id
ORDER BY g.blocked DESC,g.available_at,g.beneficiary_id LIMIT 1000;

-- Unknown requests must be investigated using the ORIGINAL affiliate:payout_id identity.
SELECT payout_id,beneficiary_id,budget_user_id,product_line,asset,amount_units,status,
 attempt_count,created_at,last_attempt_at,next_attempt_at,claimed_until,error
FROM affiliate_payouts WHERE status='UNKNOWN'
ORDER BY created_at LIMIT 1000;
ROLLBACK;
