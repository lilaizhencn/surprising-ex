package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cAdminRows.PaymentAccount;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 负责 c2c_payment_accounts；常规审核列表明确排除付款密文和完整账号。 */
@Repository
public class C2cPaymentAccountRepository {
    private final JdbcTemplate jdbc;

    public C2cPaymentAccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 一对一关联用户档案，仅为审核列表显示昵称；根记录仍按收款账号稳定分页。 */
    public List<PaymentAccount> adminPage(String status, Long userId, int count, int offset) {
        return jdbc.query("""
                SELECT a.account_id,a.user_id,a.fiat,a.method_code,a.masked_label,a.status,a.revision,a.review_reason,a.created_at,
                    COALESCE(p.nickname,'用户'||right(a.user_id::text,6)) AS nickname
                FROM c2c_payment_accounts a LEFT JOIN c2c_profiles p USING(user_id)
                WHERE (CAST(? AS text) IS NULL OR a.status=?) AND (CAST(? AS bigint) IS NULL OR a.user_id=?)
                ORDER BY a.updated_at DESC,a.account_id DESC LIMIT ? OFFSET ?
                """, (r, n) -> new PaymentAccount(r.getObject("account_id", UUID.class), r.getLong("user_id"),
                        r.getString("nickname"), r.getString("fiat"), r.getString("method_code"), r.getString("masked_label"),
                        r.getString("status"), r.getLong("revision"), r.getString("review_reason"),
                        r.getTimestamp("created_at").toInstant()), status, status, userId, userId, count, offset);
    }
}
