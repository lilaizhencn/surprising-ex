package com.surprising.affiliate.repository;

import com.surprising.affiliate.model.AffiliateModels.*;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AffiliateLedgerRepository {

  private final JdbcTemplate jdbc;

  public AffiliateLedgerRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public SourceCursor sourcePosition(String product) {
    return jdbc
      .query(
        "SELECT cluster_position,order_id FROM affiliate_source_progress WHERE product_line=?",
        (r, n) -> new SourceCursor(r.getLong(1), r.getLong(2)),
        product
      )
      .stream()
      .findFirst()
      .orElse(new SourceCursor(0, 0));
  }

  public void sourcePosition(
    String product,
    SourceCursor cursor,
    String error
  ) {
    jdbc.update(
      """
      INSERT INTO affiliate_source_progress(product_line,cluster_position,order_id,checked_at,error)
      VALUES(?,?,?,clock_timestamp(),?) ON CONFLICT(product_line) DO UPDATE SET
        order_id=CASE WHEN EXCLUDED.cluster_position>affiliate_source_progress.cluster_position THEN EXCLUDED.order_id
          WHEN EXCLUDED.cluster_position=affiliate_source_progress.cluster_position THEN GREATEST(affiliate_source_progress.order_id,EXCLUDED.order_id)
          ELSE affiliate_source_progress.order_id END,
        cluster_position=GREATEST(affiliate_source_progress.cluster_position,EXCLUDED.cluster_position),
        checked_at=EXCLUDED.checked_at,error=EXCLUDED.error
      """,
      product,
      cursor.clusterPosition(),
      cursor.orderId(),
      error
    );
  }

  public List<Map<String, Object>> sources() {
    return jdbc.queryForList(
      "SELECT product_line AS \"productLine\",cluster_position::text AS \"clusterPosition\",order_id::text AS \"orderId\",checked_at AS \"checkedAt\",error FROM affiliate_source_progress ORDER BY product_line"
    );
  }

  public boolean received(Fee fee) {
    var prior = jdbc.queryForList(
      "SELECT source_user_id,order_id,asset,fee_units,occurred_at FROM affiliate_fee_receipts WHERE product_line=? AND cluster_position=? AND order_id=?",
      fee.productLine(),
      fee.clusterPosition(),
      fee.orderId()
    );
    if (prior.isEmpty()) return false;
    var row = prior.getFirst();
    if (
      ((Number) row.get("source_user_id")).longValue() != fee.userId() ||
      ((Number) row.get("order_id")).longValue() != fee.orderId() ||
      !row.get("asset").equals(fee.asset()) ||
      ((Number) row.get("fee_units")).longValue() != fee.feeUnits() ||
      !((Timestamp) row.get("occurred_at")).toInstant().equals(fee.occurredAt())
    ) throw new IllegalStateException("费用事实身份冲突");
    return true;
  }

  public long accruePool(Fee fee, long root, Settings settings, int rate) {
    var rows = jdbc.queryForList(
      "SELECT fee_units,pool_units FROM affiliate_accrual_buckets WHERE product_line=? AND order_id=? AND root_id=? AND settings_version=? AND rate_ppm=? FOR UPDATE",
      fee.productLine(),
      fee.orderId(),
      root,
      settings.version(),
      rate
    );
    BigInteger priorFee = rows.isEmpty()
      ? BigInteger.ZERO
      : (
          (java.math.BigDecimal) rows.getFirst().get("fee_units")
        ).toBigIntegerExact();
    BigInteger priorPool = rows.isEmpty()
      ? BigInteger.ZERO
      : (
          (java.math.BigDecimal) rows.getFirst().get("pool_units")
        ).toBigIntegerExact();
    BigInteger total = priorFee.add(BigInteger.valueOf(fee.feeUnits()));
    BigInteger pool = total
      .multiply(BigInteger.valueOf(rate))
      .divide(BigInteger.valueOf(1000000));
    jdbc.update(
      """
      INSERT INTO affiliate_accrual_buckets(product_line,order_id,root_id,settings_version,rate_ppm,fee_units,pool_units)
      VALUES(?,?,?,?,?,?,?) ON CONFLICT(product_line,order_id,root_id,settings_version,rate_ppm)
      DO UPDATE SET fee_units=EXCLUDED.fee_units,pool_units=EXCLUDED.pool_units
      """,
      fee.productLine(),
      fee.orderId(),
      root,
      settings.version(),
      rate,
      new java.math.BigDecimal(total),
      new java.math.BigDecimal(pool)
    );
    return pool.subtract(priorPool).longValueExact();
  }

  public long receipt(
    Fee fee,
    Long inviter,
    Long root,
    Long settings,
    long pool,
    String disposition
  ) {
    return jdbc.queryForObject(
      """
      INSERT INTO affiliate_fee_receipts(product_line,cluster_position,source_user_id,order_id,asset,fee_units,occurred_at,
          inviter_id,root_id,settings_version,pool_units,disposition) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) RETURNING receipt_id
      """,
      Long.class,
      fee.productLine(),
      fee.clusterPosition(),
      fee.userId(),
      fee.orderId(),
      fee.asset(),
      fee.feeUnits(),
      Timestamp.from(fee.occurredAt()),
      inviter,
      root,
      settings,
      pool,
      disposition
    );
  }

  public void allocation(long receipt, Allocation row) {
    jdbc.update(
      "INSERT INTO affiliate_commissions(receipt_id,beneficiary_id,received_units,delegated_units,retained_units,term_id,share_ppm) VALUES(?,?,?,?,?,?,?)",
      receipt,
      row.userId(),
      row.receivedUnits(),
      row.delegatedUnits(),
      row.retainedUnits(),
      row.termId(),
      row.sharePpm()
    );
  }

  public List<Map<String, Object>> entries(
    long user,
    String product,
    long after,
    int limit
  ) {
    return jdbc.queryForList(
      """
      SELECT c.commission_id::text AS "commissionId",f.product_line AS "productLine",f.asset,
        f.source_user_id::text AS "sourceUserId",f.order_id::text AS "orderId",f.fee_units::text AS "feeUnits",
        c.received_units::text AS "receivedUnits",c.delegated_units::text AS "delegatedUnits",c.retained_units::text AS "retainedUnits",
        f.occurred_at AS "occurredAt",COALESCE(p.status,'UNSETTLED') AS status,p.payout_id::text AS "payoutId"
      FROM affiliate_commissions c JOIN affiliate_fee_receipts f USING(receipt_id) LEFT JOIN affiliate_payouts p USING(payout_id)
      WHERE c.beneficiary_id=? AND (?='' OR f.product_line=?) AND c.commission_id>?
      ORDER BY c.commission_id LIMIT ?
      """,
      user,
      product,
      product,
      after,
      limit
    );
  }

  public List<Map<String, Object>> payoutGroups(boolean manual, int hours) {
    return jdbc.queryForList(
      """
      SELECT DISTINCT c.beneficiary_id AS beneficiary,f.product_line AS product,f.asset,b.user_id AS budget
      FROM affiliate_commissions c JOIN affiliate_fee_receipts f USING(receipt_id)
      JOIN affiliate_profiles p ON p.user_id=c.beneficiary_id
      JOIN affiliate_budget_accounts b ON b.product_line=f.product_line
                JOIN gateway_users recipient ON recipient.user_id=p.user_id AND recipient.status='NORMAL'
                JOIN gateway_users sender ON sender.user_id=b.user_id AND sender.status IN ('NORMAL','TRADE_DISABLED')
      WHERE c.payout_id IS NULL AND c.retained_units>0 AND f.occurred_at<=clock_timestamp()-(? * interval '1 hour')
        AND (? OR (p.auto_settlement AND p.next_settlement_at<=clock_timestamp()))
        AND b.user_id<>c.beneficiary_id
      ORDER BY beneficiary,product,asset LIMIT 50
      """,
      hours,
      manual
    );
  }

  public void reserve(Map<String, Object> group, int hours) {
    long user = ((Number) group.get("beneficiary")).longValue();
    String product = group.get("product").toString(),
      asset = group.get("asset").toString();
    var rows = jdbc.queryForList(
      """
      SELECT c.commission_id,c.retained_units FROM affiliate_commissions c JOIN affiliate_fee_receipts f USING(receipt_id)
      WHERE c.beneficiary_id=? AND f.product_line=? AND f.asset=? AND c.payout_id IS NULL AND c.retained_units>0
        AND f.occurred_at<=clock_timestamp()-(? * interval '1 hour') ORDER BY c.commission_id LIMIT 1000 FOR UPDATE OF c
      """,
      user,
      product,
      asset,
      hours
    );
    if (rows.isEmpty()) return;
    long total = 0;
    int count = 0;
    for (var row : rows) {
      long amount = ((Number) row.get("retained_units")).longValue();
      if (amount > Long.MAX_VALUE - total) break;
      total += amount;
      count++;
    }
    rows = rows.subList(0, count);
    UUID id = UUID.randomUUID();
    jdbc.update(
      "INSERT INTO affiliate_payouts(payout_id,beneficiary_id,budget_user_id,product_line,asset,amount_units) VALUES(?,?,?,?,?,?)",
      id,
      user,
      group.get("budget"),
      product,
      asset,
      total
    );
    for (var row : rows)
      jdbc.update(
        "UPDATE affiliate_commissions SET payout_id=? WHERE commission_id=? AND payout_id IS NULL",
        id,
        row.get("commission_id")
      );
  }

  public void deferAutomatic(int hours) {
    jdbc.update(
      """
      UPDATE affiliate_profiles p SET next_settlement_at=clock_timestamp()+(? * interval '1 hour')
      WHERE auto_settlement AND next_settlement_at<=clock_timestamp()
        AND NOT EXISTS(SELECT 1 FROM affiliate_commissions c JOIN affiliate_fee_receipts f USING(receipt_id)
          WHERE c.beneficiary_id=p.user_id AND c.payout_id IS NULL AND c.retained_units>0
          AND f.occurred_at<=clock_timestamp()-(? * interval '1 hour'))
      """,
      hours,
      hours
    );
  }

  public List<Map<String, Object>> claimPayouts() {
    return jdbc.queryForList(
      """
      UPDATE affiliate_payouts SET claimed_until=clock_timestamp()+interval '2 minutes',last_attempt_at=clock_timestamp()
      WHERE payout_id IN(SELECT payout_id FROM affiliate_payouts WHERE status IN('PENDING','UNKNOWN')
        AND (claimed_until IS NULL OR claimed_until<clock_timestamp())
        ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 10 FOR UPDATE SKIP LOCKED)
      RETURNING *
      """
    );
  }

  public void payoutResult(
    UUID id,
    String status,
    String transfer,
    String error
  ) {
    jdbc.update(
      "UPDATE affiliate_payouts SET status=?,transfer_id=COALESCE(?,transfer_id),error=?,claimed_until=NULL,paid_at=CASE WHEN ?='PAID' THEN clock_timestamp() ELSE NULL END WHERE payout_id=? AND status IN('PENDING','UNKNOWN')",
      status,
      transfer,
      error,
      status,
      id
    );
  }

  public List<Map<String, Object>> payouts(Long user, long after, int limit) {
    return jdbc.queryForList(
      """
      SELECT payout_id::text AS "payoutId",beneficiary_id::text AS "userId",budget_user_id::text AS "budgetUserId",
        product_line AS "productLine",asset,amount_units::text AS "amountUnits",status,transfer_id AS "transferId",error,
        created_at AS "createdAt",paid_at AS "paidAt"
      FROM affiliate_payouts WHERE (CAST(? AS bigint) IS NULL OR beneficiary_id=?) ORDER BY created_at DESC LIMIT ? OFFSET ?
      """,
      user,
      user,
      limit,
      after
    );
  }

  public void retry(UUID id) {
    var rows = jdbc.queryForList(
      "SELECT * FROM affiliate_payouts WHERE payout_id=? FOR UPDATE",
      id
    );
    if (rows.isEmpty()) throw new IllegalArgumentException("结算单不存在");
    var old = rows.getFirst();
    if (
      "PAID".equals(old.get("status")) || old.get("replacement_id") != null
    ) return;
    if ("REJECTED".equals(old.get("status"))) {
      UUID replacement = UUID.randomUUID();
      jdbc.update(
        "INSERT INTO affiliate_payouts(payout_id,beneficiary_id,budget_user_id,product_line,asset,amount_units) VALUES(?,?,?,?,?,?)",
        replacement,
        old.get("beneficiary_id"),
        old.get("budget_user_id"),
        old.get("product_line"),
        old.get("asset"),
        old.get("amount_units")
      );
      jdbc.update(
        "UPDATE affiliate_commissions SET payout_id=? WHERE payout_id=?",
        replacement,
        id
      );
      jdbc.update(
        "UPDATE affiliate_payouts SET replacement_id=?,error=COALESCE(error,'')||? WHERE payout_id=?",
        replacement,
        "；已明确拒绝，替代结算单 " + replacement,
        id
      );
    }
    // Unknown intents retain their original identities and active leases.
  }
}
