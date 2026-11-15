package com.surprising.affiliate.repository;

import com.surprising.affiliate.config.AffiliateDatabase;
import com.surprising.affiliate.model.AffiliateModels.*;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.sql.PreparedStatement;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AffiliateLedgerRepository {

  private final JdbcTemplate jdbc;
  private final AffiliateDeferredFeeRepository deferredFees;
  private final AffiliatePayoutRepository payouts;

  public AffiliateLedgerRepository(AffiliateDatabase database) {
    this.jdbc = database.jdbc();
    this.deferredFees = new AffiliateDeferredFeeRepository(database);
    this.payouts = new AffiliatePayoutRepository(database);
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
    var read = readPosition(product);
    if (
      cursor.clusterPosition() > read.clusterPosition() ||
      (cursor.clusterPosition() == read.clusterPosition() &&
        cursor.orderId() > read.orderId())
    ) read = cursor;
    var pending = jdbc.queryForList(
      "SELECT cluster_position,order_id,previous_position,previous_order_id,user_id,error FROM affiliate_deferred_fees WHERE product_line=? AND resolved_at IS NULL ORDER BY cluster_position,order_id LIMIT 1",
      product
    );
    var confirmed = read;
    if (!pending.isEmpty()) {
      var first = pending.getFirst();
      confirmed = new SourceCursor(
        ((Number) first.get("previous_position")).longValue(),
        ((Number) first.get("previous_order_id")).longValue()
      );
      String held =
        "存在隔离待核算费用，确认游标保留；首笔日志 " +
        first.get("cluster_position") +
        "，订单 " +
        first.get("order_id") +
        "，来源 UID " +
        first.get("user_id") +
        "；" +
        first.get("error") +
        "；修复后后台按原身份重试";
      error = error == null ? held : error + "；" + held;
    }
    jdbc.update(
      """
      INSERT INTO affiliate_source_progress(product_line,cluster_position,order_id,read_cluster_position,read_order_id,checked_at,error)
      VALUES(?,?,?,?,?,clock_timestamp(),?) ON CONFLICT(product_line) DO UPDATE SET
        cluster_position=EXCLUDED.cluster_position,order_id=EXCLUDED.order_id,
        read_cluster_position=EXCLUDED.read_cluster_position,read_order_id=EXCLUDED.read_order_id,
        checked_at=EXCLUDED.checked_at,error=EXCLUDED.error
      """,
      product,
      confirmed.clusterPosition(),
      confirmed.orderId(),
      read.clusterPosition(),
      read.orderId(),
      error
    );
  }

  public SourceCursor readPosition(String product) {
    return jdbc
      .query(
        "SELECT read_cluster_position,read_order_id FROM affiliate_source_progress WHERE product_line=?",
        (r, n) -> new SourceCursor(r.getLong(1), r.getLong(2)),
        product
      )
      .stream()
      .findFirst()
      .orElse(new SourceCursor(0, 0));
  }

  public int deferredCount(String product, int limit) {
    return deferredFees.deferredCount(product, limit);
  }

  public void deferFee(
    Fee fee,
    SourceCursor previous,
    String error,
    int retrySeconds
  ) {
    deferredFees.deferFee(fee, previous, error, retrySeconds);
  }

  public List<Fee> deferredFees(String product, int limit) {
    return deferredFees.deferredFees(product, limit);
  }

  public void retryDeferredFee(Fee fee, String error, int seconds) {
    deferredFees.retryDeferredFee(fee, error, seconds);
  }

  public void resolveDeferredFee(Fee fee) {
    deferredFees.resolveDeferredFee(fee);
  }

  public void sourceError(String product, String error) {
    jdbc.update(
      "INSERT INTO affiliate_source_progress(product_line,checked_at,error) VALUES(?,clock_timestamp(),?) ON CONFLICT(product_line) DO UPDATE SET checked_at=EXCLUDED.checked_at,error=EXCLUDED.error",
      product,
      error
    );
  }

  public List<Map<String, Object>> sources() {
    return jdbc.queryForList(
      """
      SELECT p.product_line AS "productLine",p.cluster_position::text AS "clusterPosition",p.order_id::text AS "orderId",
        p.read_cluster_position::text AS "readClusterPosition",p.read_order_id::text AS "readOrderId",
        d.total::text AS "deferredCount",d.oldest AS "oldestDeferredAt",p.checked_at AS "checkedAt",p.error
      FROM affiliate_source_progress p LEFT JOIN LATERAL(
        SELECT count(*) AS total,min(occurred_at) AS oldest FROM affiliate_deferred_fees
        WHERE product_line=p.product_line AND resolved_at IS NULL)d ON TRUE ORDER BY p.product_line
      """
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

  public Map<Long, BigInteger> delegationBalances(
    Fee fee,
    List<TeamTerm> path
  ) {
    if (path.isEmpty()) return Map.of();
    var args = new ArrayList<Object>();
    args.add(fee.productLine());
    args.add(fee.orderId());
    args.add(fee.asset());
    for (var term : path) args.add(term.termId());
    String keys = String.join(",", Collections.nCopies(path.size(), "?"));
    var result = new HashMap<Long, BigInteger>();
    jdbc.query(
      "SELECT term_id,incoming_units FROM affiliate_delegation_buckets WHERE product_line=? AND order_id=? AND asset=? AND term_id IN (" +
        keys +
        ") FOR UPDATE",
      r -> {
        result.put(r.getLong(1), r.getBigDecimal(2).toBigIntegerExact());
      },
      args.toArray()
    );
    return result;
  }

  public void allocations(
    long receipt,
    Fee fee,
    List<Allocation> rows,
    List<TeamTerm> path,
    Map<Long, BigInteger> prior
  ) {
    jdbc.batchUpdate(
      """
      INSERT INTO affiliate_commissions(receipt_id,beneficiary_id,received_units,delegated_units,retained_units,term_id,share_ppm,product_line,asset,occurred_at)
      VALUES(?,?,?,?,?,?,?,?,?,?)
      """,
      rows,
      256,
      (PreparedStatement ps, Allocation row) -> {
        ps.setLong(1, receipt);
        ps.setLong(2, row.userId());
        ps.setLong(3, row.receivedUnits());
        ps.setLong(4, row.delegatedUnits());
        ps.setLong(5, row.retainedUnits());
        ps.setObject(6, row.termId());
        ps.setInt(7, row.sharePpm());
        ps.setString(8, fee.productLine());
        ps.setString(9, fee.asset());
        ps.setTimestamp(10, Timestamp.from(fee.occurredAt()));
      }
    );
    if (!path.isEmpty()) jdbc.batchUpdate(
      """
      INSERT INTO affiliate_delegation_buckets(product_line,order_id,asset,term_id,incoming_units) VALUES(?,?,?,?,?)
      ON CONFLICT(product_line,order_id,asset,term_id) DO UPDATE SET incoming_units=EXCLUDED.incoming_units
      """,
      java.util.stream.IntStream.range(0, path.size()).boxed().toList(),
      256,
      (PreparedStatement ps, Integer i) -> {
        TeamTerm edge = path.get(i);
        ps.setString(1, fee.productLine());
        ps.setLong(2, fee.orderId());
        ps.setString(3, fee.asset());
        ps.setLong(4, edge.termId());
        ps.setBigDecimal(
          5,
          new java.math.BigDecimal(
            prior
              .getOrDefault(edge.termId(), BigInteger.ZERO)
              .add(BigInteger.valueOf(rows.get(i).receivedUnits()))
          )
        );
      }
    );
    jdbc.update(
      """
      INSERT INTO affiliate_settlement_groups(beneficiary_id,product_line,asset,first_fee_at,available_at)
      SELECT c.beneficiary_id,c.product_line,c.asset,c.occurred_at,
        CASE WHEN p.auto_settlement THEN GREATEST(CASE WHEN c.occurred_at<=p.settlement_cutoff_at THEN '-infinity'::timestamptz ELSE p.next_settlement_at END,c.occurred_at+(s.settlement_hours*interval '1 hour')) ELSE 'infinity' END
      FROM affiliate_commissions c JOIN affiliate_profiles p ON p.user_id=c.beneficiary_id
      CROSS JOIN LATERAL(SELECT settlement_hours FROM affiliate_settings ORDER BY version DESC LIMIT 1)s
      WHERE c.receipt_id=? AND c.retained_units>0
      ON CONFLICT(beneficiary_id,product_line,asset) DO UPDATE SET first_fee_at=LEAST(affiliate_settlement_groups.first_fee_at,EXCLUDED.first_fee_at),
        available_at=CASE WHEN affiliate_settlement_groups.blocked THEN affiliate_settlement_groups.available_at ELSE LEAST(affiliate_settlement_groups.available_at,EXCLUDED.available_at) END
      """,
      receipt
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

  public List<Map<String, Object>> payoutGroups(
    boolean manual,
    int hours,
    int limit
  ) {
    return payouts.payoutGroups(manual, hours, limit);
  }

  public int reserve(Map<String, Object> group, int limit) {
    return payouts.reserve(group, limit);
  }

  public void deferAutomatic(Set<Long> users, int hours) {
    payouts.deferAutomatic(users, hours);
  }

  public Optional<Map<String, Object>> claimPayout(UUID token, int seconds) {
    return payouts.claimPayout(token, seconds);
  }

  public boolean payoutBalanced(Map<String, Object> payout) {
    return payouts.payoutBalanced(payout);
  }

  public void payoutResult(
    UUID id,
    UUID token,
    String status,
    String transfer,
    String error,
    int retrySeconds
  ) {
    payouts.payoutResult(id, token, status, transfer, error, retrySeconds);
  }

  public List<Map<String, Object>> payouts(Long user, long after, int limit) {
    return payouts.payouts(user, after, limit);
  }

  public void retry(UUID id) {
    payouts.retry(id);
  }
}
