package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cAdminRows.Merchant;
import com.surprising.gateway.c2c.model.C2cMerchant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 负责 c2c_merchants，后台关联读取只返回审核需要的脱敏投影。 */
@Repository
public class C2cMerchantRepository {
    private final JdbcTemplate jdbc;

    public C2cMerchantRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
}
