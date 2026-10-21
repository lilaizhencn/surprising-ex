package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cAdminRows.Merchant;
import com.surprising.gateway.c2c.model.C2cMerchant;
import com.surprising.gateway.c2c.model.C2cMerchantStats;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 负责 c2c_merchants，后台关联读取只返回审核需要的脱敏投影。 */
@Repository
public class C2cMerchantRepository {
    private final JdbcTemplate jdbc;

    public C2cMerchantRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 单个商家为根，关联唯一档案和订单统计只用于公开资料展示；聚合不扩大商家行数，不读取付款或KYC原件。 */
    public Optional<C2cMerchantStats> stats(long userId) {
        return jdbc.query("""
                SELECT COALESCE(p.nickname,'商家'||right(c.user_id::text,6)) AS nickname,c.status,c.tier,
                    count(o.order_id) FILTER(WHERE o.status='COMPLETED' AND o.completed_at>=now()-interval '30 days') AS completed,
                    count(o.order_id) FILTER(WHERE o.status IN ('COMPLETED','CANCELED','EXPIRED') AND COALESCE(o.completed_at,o.canceled_at)>=now()-interval '30 days') AS ended,
                    count(o.order_id) FILTER(WHERE o.status='COMPLETED') AS total,
                    avg(EXTRACT(EPOCH FROM o.paid_at-(o.payment_expires_at-o.payment_window_seconds*interval '1 second'))) FILTER(WHERE o.status='COMPLETED') AS paid_seconds,
                    avg(EXTRACT(EPOCH FROM o.completed_at-o.paid_at)) FILTER(WHERE o.status='COMPLETED') AS released_seconds
                FROM c2c_merchants c LEFT JOIN c2c_profiles p USING(user_id)
                LEFT JOIN c2c_orders o ON o.buyer_user_id=c.user_id OR o.seller_user_id=c.user_id
                WHERE c.user_id=? GROUP BY c.user_id,p.nickname,c.status,c.tier
                """, (r,n)->new C2cMerchantStats(userId,r.getString("nickname"),r.getString("status"),r.getString("tier"),
                r.getLong("completed"),r.getLong("ended"),r.getLong("total"),r.getBigDecimal("paid_seconds"),
                r.getBigDecimal("released_seconds")),userId).stream().findFirst();
    }

    public boolean approved(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_merchants WHERE user_id=? AND status='APPROVED')",
                Boolean.class, userId));
    }

    public Optional<C2cMerchant> find(long userId, boolean lock) {
        return jdbc.query("SELECT * FROM c2c_merchants WHERE user_id=?" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new C2cMerchant(r.getLong("user_id"), r.getString("status"), r.getString("tier"),
                        r.getString("application_text"), r.getString("review_reason"), r.getLong("revision"),
                        r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant()),
                userId).stream().findFirst();
    }

    public void review(long userId, String status, String tier, String reason, long reviewer) {
        jdbc.update("""
                UPDATE c2c_merchants SET status=?,tier=?,review_reason=?,reviewed_by=?,reviewed_at=now(),revision=revision+1,updated_at=now()
                WHERE user_id=?
                """, status, tier, reason, reviewer, userId);
    }

    /**
     * 以商家为分页根，一次读取昵称、KYC状态、最新质押及其资产精度用于审核列表。
     * user_id/asset均为唯一关联；LATERAL仅选最新一笔质押，不扩大行数、不读取KYC原件或付款资料。
     */
    public List<Merchant> adminPage(String status, Long userId, int count, int offset) {
        return jdbc.query("""
                SELECT c.*,COALESCE(p.nickname,'用户'||right(c.user_id::text,6)) AS nickname,
                    k.status AS kyc_status,k.expires_at AS kyc_expires_at,b.status AS bond_status,
                    b.asset AS bond_asset,b.amount_units AS bond_units,ba.scale_units AS bond_scale
                FROM c2c_merchants c LEFT JOIN c2c_profiles p USING(user_id)
                LEFT JOIN gateway_user_kyc_profiles k USING(user_id)
                LEFT JOIN LATERAL(SELECT * FROM c2c_merchant_bonds WHERE user_id=c.user_id
                    ORDER BY created_at DESC,bond_id DESC LIMIT 1) b ON true
                LEFT JOIN assets ba ON ba.asset=b.asset
                WHERE (CAST(? AS text) IS NULL OR c.status=?) AND (CAST(? AS bigint) IS NULL OR c.user_id=?)
                ORDER BY c.updated_at DESC,c.user_id DESC LIMIT ? OFFSET ?
                """, (r, n) -> new Merchant(r.getLong("user_id"), r.getString("nickname"), r.getString("status"),
                        r.getString("tier"), r.getString("application_text"), r.getString("review_reason"),
                        r.getLong("revision"), r.getString("kyc_status"),
                        r.getTimestamp("kyc_expires_at") == null ? null : r.getTimestamp("kyc_expires_at").toInstant(),
                        r.getString("bond_status"), r.getString("bond_asset"), r.getObject("bond_units", Long.class),
                        r.getLong("bond_scale"), r.getTimestamp("created_at").toInstant()),
                status, status, userId, userId, count, offset);
    }
    public void apply(long userId,String application) {
        jdbc.update("INSERT INTO c2c_merchants(user_id,application_text) VALUES(?,?)",userId,application);
    }
    public void reapply(long userId,String application) {
        jdbc.update("""
                UPDATE c2c_merchants SET status='PENDING',application_text=?,review_reason=NULL,reviewed_by=NULL,reviewed_at=NULL,
                    revision=revision+1,updated_at=now() WHERE user_id=?
                """,application,userId);
    }

}
