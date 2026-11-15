package com.surprising.affiliate.repository;

import com.surprising.affiliate.config.AffiliateDatabase;
import java.sql.PreparedStatement;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 返佣结算与打款仓储：负责结算分组、预留结算单、租约认领、打款状态流转与重试。
 */
@Repository
public class AffiliatePayoutRepository {

  private final JdbcTemplate jdbc;

  public AffiliatePayoutRepository(AffiliateDatabase database) {
    this.jdbc = database.jdbc();
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
