package com.surprising.affiliate.repository;

import com.surprising.affiliate.config.AffiliateDatabase;
import com.surprising.affiliate.model.AffiliateModels.*;
import com.surprising.affiliate.model.AffiliateRelationshipException;
import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Affiliate aggregate persistence; no trading balance writes or online Core reads. */
@Repository
public class AffiliateRepository {

  private final JdbcTemplate jdbc;
  private final AffiliateTeamRepository team;

  public AffiliateRepository(AffiliateDatabase database) {
    this.jdbc = database.jdbc();
    this.team = new AffiliateTeamRepository(database);
  }

  public void lock() {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(714280031)");
  }

  public Instant now() {
    return jdbc
      .queryForObject("SELECT clock_timestamp()", Timestamp.class)
      .toInstant();
  }

  public void ensureProfile(long userId, String code) {
    jdbc.update(
      """
      INSERT INTO affiliate_profiles(user_id,invite_code,next_settlement_at)
      SELECT user_id,?,clock_timestamp()+((SELECT settlement_hours FROM affiliate_settings ORDER BY version DESC LIMIT 1)*interval '1 hour')
      FROM gateway_users WHERE user_id=?
      ON CONFLICT(user_id) DO NOTHING
      """,
      code,
      userId
    );
  }

  public Profile profile(long userId) {
    return findProfile(userId).orElseThrow(() ->
      new IllegalArgumentException("用户不存在")
    );
  }

  public Optional<Profile> findProfile(long userId) {
    return jdbc
      .query(
        "SELECT * FROM affiliate_profiles WHERE user_id=?",
        (r, n) ->
          new Profile(
            r.getString("user_id"),
            r.getString("invite_code"),
            r.getBoolean("kol_enabled"),
            r.getBoolean("auto_settlement"),
            (Integer) r.getObject("rate_ppm"),
            r.getTimestamp("created_at").toInstant()
          ),
        userId
      )
      .stream()
      .findFirst();
  }

  public Optional<Long> codeOwner(String code) {
    return jdbc
      .query(
        "SELECT user_id FROM affiliate_profiles WHERE invite_code=?",
        (r, n) -> r.getLong(1),
        code
      )
      .stream()
      .findFirst();
  }

  public Settings settings(Instant at) {
    return jdbc
      .query(
        "SELECT * FROM affiliate_settings WHERE effective_at<=? ORDER BY version DESC LIMIT 1",
        (r, n) ->
          new Settings(
            r.getLong("version"),
            r.getInt("referral_rate_ppm"),
            r.getInt("kol_rate_ppm"),
            r.getInt("settlement_hours")
          ),
        Timestamp.from(at)
      )
      .stream()
      .findFirst()
      .orElseThrow(() -> new IllegalStateException("返佣配置尚未生效"));
  }

  public void settings(SettingsInput input, long actor) {
    jdbc.update(
      "INSERT INTO affiliate_settings(referral_rate_ppm,kol_rate_ppm,settlement_hours,actor_id,reason) VALUES(?,?,?,?,?)",
      input.referralRatePpm(),
      input.kolRatePpm(),
      input.settlementHours(),
      actor,
      input.reason()
    );
    jdbc.update(
      "UPDATE affiliate_profiles SET next_settlement_at=LEAST(next_settlement_at,clock_timestamp()+(? * interval '1 hour'))",
      input.settlementHours()
    );
    jdbc.update(
      """
      UPDATE affiliate_settlement_groups g SET available_at=CASE WHEN p.auto_settlement THEN GREATEST(CASE WHEN g.first_fee_at<=p.settlement_cutoff_at THEN '-infinity'::timestamptz ELSE p.next_settlement_at END,g.first_fee_at+(?*interval '1 hour')) ELSE 'infinity' END,blocked=FALSE
      FROM affiliate_profiles p WHERE p.user_id=g.beneficiary_id
      """,
      input.settlementHours()
    );
  }

  public void configure(long userId, ProfileInput input, long actor) {
    jdbc.update(
      "UPDATE affiliate_profiles SET kol_suspended=CASE WHEN ? THEN FALSE WHEN kol_enabled THEN TRUE ELSE kol_suspended END,kol_enabled=?,auto_settlement=?,rate_ppm=? WHERE user_id=?",
      input.kolEnabled(),
      input.kolEnabled(),
      input.autoSettlement(),
      input.ratePpm(),
      userId
    );
    jdbc.update(
      "INSERT INTO affiliate_profile_history(user_id,kol_enabled,auto_settlement,rate_ppm,actor_id,reason) VALUES(?,?,?,?,?,?)",
      userId,
      input.kolEnabled(),
      input.autoSettlement(),
      input.ratePpm(),
      actor,
      input.reason()
    );
    jdbc.update(
      """
      UPDATE affiliate_settlement_groups g SET available_at=CASE WHEN ? THEN GREATEST(CASE WHEN g.first_fee_at<=p.settlement_cutoff_at THEN '-infinity'::timestamptz ELSE p.next_settlement_at END,g.first_fee_at+(s.settlement_hours*interval '1 hour')) ELSE 'infinity' END,blocked=FALSE
      FROM affiliate_profiles p CROSS JOIN LATERAL(SELECT settlement_hours FROM affiliate_settings ORDER BY version DESC LIMIT 1)s
      WHERE p.user_id=g.beneficiary_id AND p.user_id=?
      """,
      input.autoSettlement(),
      userId
    );
    audit(actor, "PROFILE", userId, input.reason());
  }

  public boolean suspended(long userId) {
    return Boolean.TRUE.equals(
      jdbc.queryForObject(
        "SELECT kol_suspended FROM affiliate_profiles WHERE user_id=?",
        Boolean.class,
        userId
      )
    );
  }

  public Optional<Long> inviter(long userId, Instant at) {
    return jdbc
      .query(
        "SELECT inviter_id FROM affiliate_referrals WHERE user_id=? AND accepted_at<=?",
        (r, n) -> r.getLong(1),
        userId,
        Timestamp.from(at)
      )
      .stream()
      .findFirst();
  }

  public void bind(long user, long inviter) {
    jdbc.update(
      "INSERT INTO affiliate_referrals(user_id,inviter_id) VALUES(?,?)",
      user,
      inviter
    );
    audit(user, "ACCEPT_REFERRAL", inviter, "接受邀请");
  }

  public boolean descendant(long ancestor, long user) {
    if (ancestor == user) return true;
    return Boolean.TRUE.equals(
      jdbc.queryForObject(
        """
        WITH RECURSIVE ancestors(id) AS (
          SELECT inviter_id FROM affiliate_referrals WHERE user_id=?
          UNION SELECT r.inviter_id FROM affiliate_referrals r JOIN ancestors a ON r.user_id=a.id
        ) SELECT EXISTS(SELECT 1 FROM ancestors WHERE id=?)
        """,
        Boolean.class,
        user,
        ancestor
      )
    );
  }

  public Optional<TeamTerm> parent(long user, Instant at) {
    return jdbc
      .query(
        """
        SELECT term_id,parent_id,child_id,share_ppm FROM affiliate_team_terms
        WHERE child_id=? AND effective_from<=? AND (effective_until IS NULL OR effective_until>?)
        ORDER BY effective_from DESC,term_id DESC LIMIT 1
        """,
        (r, n) ->
          new TeamTerm(r.getLong(1), r.getLong(2), r.getLong(3), r.getInt(4)),
        user,
        Timestamp.from(at),
        Timestamp.from(at)
      )
      .stream()
      .findFirst();
  }

  public List<PathNode> lineage(long user, Instant at, int limit) {
    return jdbc.query(
      """
      WITH RECURSIVE path(user_id,term_id,parent_id,share_ppm) AS (
        SELECT u.id,t.term_id,t.parent_id,t.share_ppm FROM (VALUES(CAST(? AS bigint))) u(id)
        LEFT JOIN affiliate_team_terms t ON t.child_id=u.id AND t.effective_from<=?
          AND (t.effective_until IS NULL OR t.effective_until>?)
        UNION
        SELECT p.parent_id,t.term_id,t.parent_id,t.share_ppm FROM path p
        LEFT JOIN affiliate_team_terms t ON t.child_id=p.parent_id AND t.effective_from<=?
          AND (t.effective_until IS NULL OR t.effective_until>?) WHERE p.parent_id IS NOT NULL
      ) SELECT p.*,u.user_id IS NOT NULL AS present,COALESCE(h.kol_enabled,FALSE) AS enabled,h.rate_ppm
      FROM path p LEFT JOIN affiliate_profiles u ON u.user_id=p.user_id AND u.created_at<=?
      LEFT JOIN LATERAL(SELECT kol_enabled,rate_ppm FROM affiliate_profile_history
        WHERE user_id=p.user_id AND effective_at<=? ORDER BY effective_at DESC,version DESC LIMIT 1) h ON TRUE
      LIMIT ?
      """,
      (r, n) -> {
        if (!r.getBoolean("present")) throw new AffiliateRelationshipException(
          "团队历史资料不完整，核算已暂停"
        );
        long id = r.getLong("user_id");
        Long term = (Long) r.getObject("term_id");
        return new PathNode(
          new Node(
            id,
            r.getBoolean("enabled"),
            (Integer) r.getObject("rate_ppm")
          ),
          term == null
            ? null
            : new TeamTerm(
                term,
                r.getLong("parent_id"),
                id,
                r.getInt("share_ppm")
              )
        );
      },
      user,
      Timestamp.from(at),
      Timestamp.from(at),
      Timestamp.from(at),
      Timestamp.from(at),
      Timestamp.from(at),
      Timestamp.from(at),
      limit
    );
  }

  /** Writers use the exclusive configuration lock; independent products may read concurrently. */
  public void readLock() {
    jdbc.queryForList("SELECT pg_advisory_xact_lock_shared(714280031)");
  }

  public boolean tryReadLock() {
    return Boolean.TRUE.equals(
      jdbc.queryForObject(
        "SELECT pg_try_advisory_xact_lock_shared(714280031)",
        Boolean.class
      )
    );
  }

  public boolean tryProductLock(String product) {
    int key = ProductLine.valueOf(product).name().hashCode();
    return Boolean.TRUE.equals(
      jdbc.queryForObject(
        "SELECT pg_try_advisory_xact_lock(714280032,?)",
        Boolean.class,
        key
      )
    );
  }

  public boolean trySettlementLock() {
    return Boolean.TRUE.equals(
      jdbc.queryForObject(
        "SELECT pg_try_advisory_xact_lock(714280033)",
        Boolean.class
      )
    );
  }

  public void productLock(String product) {
    jdbc.queryForList(
      "SELECT pg_advisory_xact_lock(714280032,?)",
      ProductLine.valueOf(product).name().hashCode()
    );
  }

  public void term(long parent, long child, int share, long actor) {
    team.term(parent, child, share, actor, now());
    audit(actor, "TEAM_SHARE", child, "sharePpm=" + share);
  }

  public Invitation invitation(String code) {
    return team.invitation(code);
  }

  public void createInvitation(
    String code,
    long inviter,
    Long target,
    int share
  ) {
    team.createInvitation(code, inviter, target, share);
  }

  public void acceptInvitation(String code, long user) {
    team.acceptInvitation(code, user);
  }

  public List<Invitation> invitations(long user) {
    return team.invitations(user);
  }

  public List<Map<String, Object>> members(long parent, long after, int limit) {
    return team.members(parent, after, limit);
  }

  public List<Map<String, Object>> memberContributions(
    long parent,
    List<Long> members
  ) {
    return team.memberContributions(parent, members);
  }

  public List<Map<String, Object>> summary(long user, String product) {
    return jdbc.queryForList(
      """
      SELECT f.product_line AS "productLine",f.asset,
        sum(c.received_units)::text AS "receivedUnits",sum(c.delegated_units)::text AS "delegatedUnits",
        sum(c.retained_units)::text AS "retainedUnits",
        COALESCE(sum(c.retained_units) FILTER(WHERE c.payout_id IS NULL),0)::text AS "pendingUnits",
        COALESCE(sum(c.retained_units) FILTER(WHERE p.status='PAID'),0)::text AS "paidUnits",
        COALESCE(sum(c.retained_units) FILTER(WHERE c.payout_id IS NOT NULL AND p.status<>'PAID'),0)::text AS "inFlightUnits",
        sum(f.fee_units)::text AS "teamFeeUnits",count(DISTINCT f.source_user_id)::text AS "tradingUsers",
        COALESCE(sum(f.fee_units) FILTER(WHERE f.inviter_id=?),0)::text AS "directFeeUnits"
      FROM affiliate_commissions c JOIN affiliate_fee_receipts f USING(receipt_id)
      LEFT JOIN affiliate_payouts p USING(payout_id)
      WHERE c.beneficiary_id=? AND (?='' OR f.product_line=?) GROUP BY f.product_line,f.asset
      ORDER BY f.product_line,f.asset
      """,
      user,
      user,
      product,
      product
    );
  }

  public List<Map<String, Object>> counts(long user) {
    return jdbc.queryForList(
      """
      WITH RECURSIVE team(id) AS (
        SELECT user_id FROM affiliate_referrals WHERE inviter_id=?
        UNION SELECT r.user_id FROM team t CROSS JOIN LATERAL(SELECT user_id FROM affiliate_referrals WHERE inviter_id=t.id OFFSET 0) r
      ) SELECT (SELECT count(*) FROM affiliate_referrals WHERE inviter_id=?)::text AS "directInviteCount",
        (SELECT count(*) FROM team)::text AS "teamInviteCount"
      """,
      user,
      user
    );
  }

  public List<Map<String, Object>> profiles(long after, int limit) {
    return jdbc.queryForList(
      "SELECT user_id::text AS \"userId\",invite_code AS \"inviteCode\",kol_enabled AS \"kolEnabled\",auto_settlement AS \"autoSettlement\",rate_ppm AS \"ratePpm\" FROM affiliate_profiles WHERE user_id>? ORDER BY user_id LIMIT ?",
      after,
      limit
    );
  }

  public void budget(String product, long user, long actor, String reason) {
    jdbc.update(
      """
      INSERT INTO affiliate_budget_accounts(product_line,user_id,actor_id,reason) VALUES(?,?,?,?)
      ON CONFLICT(product_line) DO UPDATE SET user_id=EXCLUDED.user_id,actor_id=EXCLUDED.actor_id,
        reason=EXCLUDED.reason,updated_at=clock_timestamp()
      """,
      product,
      user,
      actor,
      reason
    );
    jdbc.update(
      """
      UPDATE affiliate_settlement_groups g SET available_at=CASE WHEN p.auto_settlement THEN GREATEST(CASE WHEN g.first_fee_at<=p.settlement_cutoff_at THEN '-infinity'::timestamptz ELSE p.next_settlement_at END,g.first_fee_at+(s.settlement_hours*interval '1 hour')) ELSE 'infinity' END,blocked=FALSE
      FROM affiliate_profiles p CROSS JOIN LATERAL(SELECT settlement_hours FROM affiliate_settings ORDER BY version DESC LIMIT 1)s
      WHERE p.user_id=g.beneficiary_id AND g.product_line=?
      """,
      product
    );
    audit(actor, "BUDGET", user, product + ": " + reason);
  }

  public List<Map<String, Object>> budgets() {
    return jdbc.queryForList(
      "SELECT product_line AS \"productLine\",user_id::text AS \"userId\",updated_at AS \"updatedAt\" FROM affiliate_budget_accounts ORDER BY product_line"
    );
  }

  public void audit(long actor, String action, Long target, String detail) {
    jdbc.update(
      "INSERT INTO affiliate_audit(actor_id,action,target_id,detail) VALUES(?,?,?,?)",
      actor,
      action,
      target,
      detail
    );
  }
}
