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

  public AffiliateLedgerRepository(AffiliateDatabase database) {
    this.jdbc = database.jdbc();
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
    return jdbc.queryForObject(
      "SELECT count(*) FROM (SELECT 1 FROM affiliate_deferred_fees WHERE product_line=? AND resolved_at IS NULL LIMIT ?) d",
      Integer.class,
      product,
      limit
    );
  }

  public void deferFee(
    Fee fee,
    SourceCursor previous,
    String error,
    int retrySeconds
  ) {
    jdbc.update(
      """
      INSERT INTO affiliate_deferred_fees(product_line,cluster_position,order_id,user_id,asset,fee_units,occurred_at,previous_position,previous_order_id,error,next_attempt_at)
      VALUES(?,?,?,?,?,?,?,?,?,?,clock_timestamp()+(?*interval '1 second'))
      """,
      fee.productLine(),
      fee.clusterPosition(),
      fee.orderId(),
      fee.userId(),
      fee.asset(),
      fee.feeUnits(),
      Timestamp.from(fee.occurredAt()),
      previous.clusterPosition(),
      previous.orderId(),
      error.substring(0, Math.min(500, error.length())),
      retrySeconds
    );
  }

  public List<Fee> deferredFees(String product, int limit) {
    return jdbc.query(
      """
      SELECT d.*,f.user_id AS fact_user,f.asset AS fact_asset,f.fee_units AS fact_units,f.occurred_at AS fact_time
      FROM affiliate_deferred_fees d JOIN core_trade_fee_facts f
        ON f.product_line=d.product_line AND f.order_id=d.order_id AND f.cluster_position=d.cluster_position
      WHERE d.product_line=? AND d.resolved_at IS NULL AND d.next_attempt_at<=clock_timestamp()
      ORDER BY d.next_attempt_at,d.cluster_position,d.order_id LIMIT ?
      """,
      (r, n) -> {
        var fee = new Fee(
          r.getLong("cluster_position"),
          r.getString("product_line"),
          r.getLong("user_id"),
          r.getLong("order_id"),
          r.getString("asset"),
          r.getLong("fee_units"),
          r.getTimestamp("occurred_at").toInstant()
        );
        if (
          fee.userId() != r.getLong("fact_user") ||
          !fee.asset().equals(r.getString("fact_asset")) ||
          fee.feeUnits() != r.getLong("fact_units") ||
          !fee.occurredAt().equals(r.getTimestamp("fact_time").toInstant())
        ) throw new IllegalStateException(
          "隔离费用与权威来源身份冲突，产品核算已暂停"
        );
        return fee;
      },
      product,
      limit
    );
  }

  public void retryDeferredFee(Fee fee, String error, int seconds) {
    jdbc.update(
      "UPDATE affiliate_deferred_fees SET attempt_count=attempt_count+1,last_attempt_at=clock_timestamp(),next_attempt_at=clock_timestamp()+(?*interval '1 second'),error=? WHERE product_line=? AND cluster_position=? AND order_id=? AND resolved_at IS NULL",
      seconds,
      error.substring(0, Math.min(500, error.length())),
      fee.productLine(),
      fee.clusterPosition(),
      fee.orderId()
    );
  }

  public void resolveDeferredFee(Fee fee) {
    int changed = jdbc.update(
      """
      UPDATE affiliate_deferred_fees d SET resolved_at=clock_timestamp(),receipt_id=f.receipt_id,error='已恢复核算',last_attempt_at=clock_timestamp()
      FROM affiliate_fee_receipts f WHERE d.product_line=? AND d.cluster_position=? AND d.order_id=? AND d.resolved_at IS NULL
        AND f.product_line=d.product_line AND f.cluster_position=d.cluster_position AND f.order_id=d.order_id
      """,
      fee.productLine(),
      fee.clusterPosition(),
      fee.orderId()
    );
    if (changed != 1) throw new IllegalStateException(
      "隔离费用缺少唯一已提交核算凭证"
    );
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
    String order = manual
      ? "g.checked_at NULLS FIRST,g.beneficiary_id,g.product_line,g.asset"
      : "g.available_at,g.beneficiary_id,g.product_line,g.asset";
    return jdbc.queryForList(
      """
      SELECT g.beneficiary_id AS beneficiary,g.product_line AS product,g.asset,b.user_id AS budget,
        recipient.status AS recipient_status,sender.status AS sender_status,
        CASE WHEN ? OR p.next_settlement_at<=statement_timestamp() THEN statement_timestamp()-(?*interval '1 hour') ELSE p.settlement_cutoff_at END AS cutoff
      FROM affiliate_settlement_groups g JOIN affiliate_profiles p ON p.user_id=g.beneficiary_id
      LEFT JOIN affiliate_budget_accounts b ON b.product_line=g.product_line
      LEFT JOIN gateway_users sender ON sender.user_id=b.user_id
      JOIN gateway_users recipient ON recipient.user_id=p.user_id
      WHERE g.first_fee_at<=clock_timestamp()-(?*interval '1 hour')
        AND (? OR (g.available_at<=clock_timestamp() AND p.auto_settlement AND
          (p.next_settlement_at<=clock_timestamp() OR g.first_fee_at<=p.settlement_cutoff_at)))
      """ +
        " ORDER BY " +
        order +
        " LIMIT ? FOR UPDATE OF g SKIP LOCKED",
      manual,
      hours,
      hours,
      manual,
      limit
    );
  }

  public int reserve(Map<String, Object> group, int limit) {
    long user = ((Number) group.get("beneficiary")).longValue();
    String product = group.get("product").toString(),
      asset = group.get("asset").toString();
    boolean sender =
      "NORMAL".equals(group.get("sender_status")) ||
      "TRADE_DISABLED".equals(group.get("sender_status"));
    if (
      group.get("budget") == null ||
      !sender ||
      !"NORMAL".equals(group.get("recipient_status")) ||
      ((Number) group.get("budget")).longValue() == user
    ) {
      jdbc.update(
        "UPDATE affiliate_settlement_groups SET checked_at=clock_timestamp(),available_at=clock_timestamp()+interval '1 minute',blocked=TRUE WHERE beneficiary_id=? AND product_line=? AND asset=?",
        user,
        product,
        asset
      );
      return 0;
    }
    var rows = jdbc.queryForList(
      """
      SELECT commission_id,retained_units FROM affiliate_commissions
      WHERE beneficiary_id=? AND product_line=? AND asset=? AND payout_id IS NULL AND retained_units>0
        AND occurred_at<=?
      ORDER BY occurred_at,commission_id LIMIT ? FOR UPDATE
      """,
      user,
      product,
      asset,
      group.get("cutoff"),
      limit
    );
    long total = 0;
    int count = 0;
    for (var row : rows) {
      long amount = ((Number) row.get("retained_units")).longValue();
      if (amount > Long.MAX_VALUE - total) break;
      total += amount;
      count++;
    }
    if (count > 0) {
      List<Map<String, Object>> selected = rows.subList(0, count);
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
      jdbc.batchUpdate(
        "UPDATE affiliate_commissions SET payout_id=? WHERE commission_id=? AND payout_id IS NULL",
        selected,
        256,
        (PreparedStatement ps, Map<String, Object> row) -> {
          ps.setObject(1, id);
          ps.setLong(2, ((Number) row.get("commission_id")).longValue());
        }
      );
      if (
        !payoutBalanced(
          Map.of(
            "payout_id",
            id,
            "amount_units",
            total,
            "beneficiary_id",
            user,
            "product_line",
            product,
            "asset",
            asset
          )
        )
      ) throw new IllegalStateException("结算单关联应付不守恒，准备事务已回滚");
    }
    var remaining = jdbc.query(
      """
      SELECT occurred_at FROM affiliate_commissions WHERE beneficiary_id=? AND product_line=? AND asset=? AND payout_id IS NULL AND retained_units>0
      ORDER BY occurred_at,commission_id LIMIT 1
      """,
      (r, n) -> r.getTimestamp(1),
      user,
      product,
      asset
    );
    if (remaining.isEmpty()) jdbc.update(
      "DELETE FROM affiliate_settlement_groups WHERE beneficiary_id=? AND product_line=? AND asset=?",
      user,
      product,
      asset
    );
    else jdbc.update(
      "UPDATE affiliate_settlement_groups SET first_fee_at=?,available_at=clock_timestamp(),blocked=FALSE,checked_at=clock_timestamp() WHERE beneficiary_id=? AND product_line=? AND asset=?",
      remaining.getFirst(),
      user,
      product,
      asset
    );
    return count;
  }

  public void deferAutomatic(Set<Long> users, int hours) {
    if (users.isEmpty()) return;
    var args = new ArrayList<Object>();
    args.add(hours);
    args.add(hours);
    args.add(hours);
    args.addAll(users);
    String keys = String.join(",", Collections.nCopies(users.size(), "?"));
    jdbc.update(
      """
      UPDATE affiliate_profiles p SET
        settlement_cutoff_at=CASE WHEN p.next_settlement_at<=statement_timestamp() THEN statement_timestamp()-(?*interval '1 hour') ELSE p.settlement_cutoff_at END,
        next_settlement_at=CASE WHEN p.next_settlement_at>statement_timestamp() THEN p.next_settlement_at WHEN EXISTS(
        SELECT 1 FROM affiliate_commissions c WHERE c.beneficiary_id=p.user_id AND c.payout_id IS NULL AND c.retained_units>0
          AND c.occurred_at<=statement_timestamp()-(?*interval '1 hour')) THEN statement_timestamp()
        ELSE statement_timestamp()+(?*interval '1 hour') END
      WHERE p.auto_settlement AND p.user_id IN (""" +
        keys +
        ")",
      args.toArray()
    );
    var queueArgs = new ArrayList<Object>();
    queueArgs.add(hours);
    queueArgs.addAll(users);
    jdbc.update(
      """
      UPDATE affiliate_settlement_groups g SET available_at=CASE WHEN p.auto_settlement
        THEN GREATEST(CASE WHEN g.first_fee_at<=p.settlement_cutoff_at THEN '-infinity'::timestamptz ELSE p.next_settlement_at END,g.first_fee_at+(?*interval '1 hour')) ELSE 'infinity' END
      FROM affiliate_profiles p WHERE p.user_id=g.beneficiary_id AND NOT g.blocked AND p.user_id IN (""" +
        keys +
        ")",
      queueArgs.toArray()
    );
  }

  public Optional<Map<String, Object>> claimPayout(UUID token, int seconds) {
    return jdbc
      .queryForList(
        """
        UPDATE affiliate_payouts SET claimed_until=clock_timestamp()+(?*interval '1 second'),claim_token=?,attempt_count=attempt_count+1,last_attempt_at=clock_timestamp()
        WHERE payout_id=(SELECT payout_id FROM affiliate_payouts WHERE status IN('PENDING','UNKNOWN') AND next_attempt_at<=clock_timestamp()
          AND (claimed_until IS NULL OR claimed_until<clock_timestamp()) ORDER BY next_attempt_at,last_attempt_at NULLS FIRST,created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
        RETURNING *
        """,
        seconds,
        token
      )
      .stream()
      .findFirst();
  }

  public boolean payoutBalanced(Map<String, Object> payout) {
    UUID id = (UUID) payout.get("payout_id");
    return Boolean.TRUE.equals(
      jdbc.queryForObject(
        """
        SELECT COALESCE(sum(retained_units),0)=? AND count(*)>0 AND bool_and(beneficiary_id=? AND product_line=? AND asset=?)
        FROM affiliate_commissions WHERE payout_id=?
        """,
        Boolean.class,
        payout.get("amount_units"),
        payout.get("beneficiary_id"),
        payout.get("product_line"),
        payout.get("asset"),
        id
      )
    );
  }

  public void payoutResult(
    UUID id,
    UUID token,
    String status,
    String transfer,
    String error,
    int retrySeconds
  ) {
    jdbc.update(
      """
      UPDATE affiliate_payouts SET status=?,transfer_id=COALESCE(?,transfer_id),error=?,claimed_until=NULL,
        next_attempt_at=clock_timestamp()+(?*interval '1 second'),paid_at=CASE WHEN ?='PAID' THEN clock_timestamp() ELSE NULL END
      WHERE payout_id=? AND claim_token=? AND status IN('PENDING','UNKNOWN')
      """,
      status,
      transfer,
      error,
      retrySeconds,
      status,
      id,
      token
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
        "UPDATE affiliate_payouts SET replacement_id=?,error=COALESCE(error,'')||? WHERE payout_id=?",
        replacement,
        "；已明确拒绝，替代结算单 " + replacement,
        id
      );
      jdbc.update(
        "UPDATE affiliate_commissions SET payout_id=? WHERE payout_id=?",
        replacement,
        id
      );
    }
    // Unknown intents retain their original identities and active leases.
  }
}
