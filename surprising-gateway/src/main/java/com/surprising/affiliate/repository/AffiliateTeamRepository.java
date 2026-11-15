package com.surprising.affiliate.repository;

import com.surprising.affiliate.config.AffiliateDatabase;
import com.surprising.affiliate.model.AffiliateModels.Invitation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 代理团队关系与邀请仓储：负责团队分成条款、邀请码生成/接受、团队成员列表及贡献统计。
 */
@Repository
public class AffiliateTeamRepository {

  private final JdbcTemplate jdbc;

  public AffiliateTeamRepository(AffiliateDatabase database) {
    this.jdbc = database.jdbc();
  }

  public void term(long parent, long child, int share, long actor, Instant at) {
    jdbc.update(
      "UPDATE affiliate_team_terms SET effective_until=? WHERE child_id=? AND effective_until IS NULL",
      Timestamp.from(at),
      child
    );
    jdbc.update(
      "INSERT INTO affiliate_team_terms(parent_id,child_id,share_ppm,effective_from,actor_id) VALUES(?,?,?,?,?)",
      parent,
      child,
      share,
      Timestamp.from(at),
      actor
    );
  }

  public Invitation invitation(String code) {
    return jdbc
      .query(
        "SELECT * FROM affiliate_invitations WHERE token=?",
        (r, n) ->
          new Invitation(
            r.getString("token"),
            r.getString("inviter_id"),
            r.getString("target_id"),
            true,
            r.getInt("share_ppm"),
            r.getTimestamp("expires_at").toInstant(),
            r.getString("accepted_by")
          ),
        code
      )
      .stream()
      .findFirst()
      .orElseThrow(() -> new IllegalArgumentException("邀请码不存在或已失效"));
  }

  public void createInvitation(
    String code,
    long inviter,
    Long target,
    int share
  ) {
    jdbc.update(
      "INSERT INTO affiliate_invitations(token,inviter_id,target_id,share_ppm,expires_at) VALUES(?,?,?,?,clock_timestamp()+interval '7 days')",
      code,
      inviter,
      target,
      share
    );
  }

  public void acceptInvitation(String code, long user) {
    jdbc.update(
      "UPDATE affiliate_invitations SET accepted_by=?,accepted_at=clock_timestamp() WHERE token=? AND accepted_by IS NULL",
      user,
      code
    );
  }

  public List<Invitation> invitations(long user) {
    return jdbc.query(
      """
      SELECT * FROM affiliate_invitations WHERE (target_id=? OR inviter_id=?) AND accepted_by IS NULL
      AND expires_at>clock_timestamp() ORDER BY created_at DESC LIMIT 100
      """,
      (r, n) ->
        new Invitation(
          r.getString("token"),
          r.getString("inviter_id"),
          r.getString("target_id"),
          true,
          r.getInt("share_ppm"),
          r.getTimestamp("expires_at").toInstant(),
          r.getString("accepted_by")
        ),
      user,
      user
    );
  }

  public List<Map<String, Object>> members(long parent, long after, int limit) {
    return jdbc.queryForList(
      """
      SELECT p.user_id::text AS "userId",p.kol_enabled AS "kolEnabled",r.accepted_at AS "acceptedAt",
        t.share_ppm AS "sharePpm",t.parent_id::text AS "teamParentId",
        (SELECT count(*)::text FROM affiliate_referrals c WHERE c.inviter_id=p.user_id) AS "directInviteCount"
      FROM affiliate_referrals r JOIN affiliate_profiles p ON p.user_id=r.user_id
      LEFT JOIN affiliate_team_terms t ON t.child_id=p.user_id AND t.effective_until IS NULL
      WHERE r.inviter_id=? AND p.user_id>? ORDER BY p.user_id LIMIT ?
      """,
      parent,
      after,
      limit
    );
  }

  public List<Map<String, Object>> memberContributions(
    long parent,
    List<Long> members
  ) {
    if (members.isEmpty()) return List.of();
    var args = new java.util.ArrayList<Object>();
    args.add(parent);
    args.addAll(members);
    args.add(parent);
    args.add(parent);
    String placeholders = String.join(
      ",",
      java.util.Collections.nCopies(members.size(), "?")
    );
    return jdbc.queryForList(
      """
      WITH selected AS (SELECT user_id FROM affiliate_referrals WHERE inviter_id=? AND user_id IN (
      """ +
        placeholders +
        """
        )), contributed AS (
          SELECT s.user_id AS branch,f.receipt_id FROM selected s JOIN affiliate_fee_receipts f ON f.source_user_id=s.user_id
            WHERE f.inviter_id=?
          UNION
          SELECT s.user_id,c.receipt_id FROM selected s JOIN affiliate_commissions c ON c.beneficiary_id=s.user_id
        ) SELECT b.branch::text AS "userId",f.product_line AS "productLine",f.asset,
          sum(f.fee_units)::text AS "feeUnits",sum(c.received_units)::text AS "receivedUnits",sum(c.retained_units)::text AS "retainedUnits"
        FROM contributed b JOIN affiliate_fee_receipts f USING(receipt_id)
          JOIN affiliate_commissions c ON c.receipt_id=f.receipt_id AND c.beneficiary_id=?
        GROUP BY b.branch,f.product_line,f.asset ORDER BY b.branch,f.product_line,f.asset
        """,
      args.toArray()
    );
  }
}
