package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cAdminRows.PaymentAccount;
import com.surprising.gateway.c2c.model.C2cPaymentAccount;
import com.surprising.gateway.c2c.model.C2cPaymentOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 负责 c2c_payment_accounts；常规审核列表明确排除付款密文和完整账号。 */
@Repository
public class C2cPaymentAccountRepository {
    private final JdbcTemplate jdbc;

    public C2cPaymentAccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 当前广告明确列出的本人账号为根，关联付款方式仅判断启用及法币；只选脱敏三字段，不选择密文。 */
    public List<C2cPaymentOption> approvedOptions(long userId, List<UUID> accountIds) {
        return jdbc.query("""
                SELECT a.account_id,a.method_code,a.masked_label FROM c2c_payment_accounts a
                JOIN c2c_payment_methods m ON m.code=a.method_code
                WHERE a.user_id=? AND a.account_id=ANY(?::uuid[]) AND a.status='APPROVED' AND m.enabled AND a.fiat=ANY(m.currencies)
                ORDER BY a.account_id
                """, (r, n) -> new C2cPaymentOption(r.getObject("account_id", UUID.class), r.getString("method_code"),
                        r.getString("masked_label")), userId, "{" + String.join(",", accountIds.stream().map(UUID::toString).toList()) + "}");
    }

    public List<C2cPaymentAccount> byUser(long userId) {
        return jdbc.query("SELECT * FROM c2c_payment_accounts WHERE user_id=? ORDER BY created_at DESC,account_id", this::read, userId);
    }

    public Optional<C2cPaymentAccount> find(long userId, UUID id) {
        return jdbc.query("SELECT * FROM c2c_payment_accounts WHERE user_id=? AND account_id=?", this::read, userId, id)
                .stream().findFirst();
    }

    public Optional<C2cPaymentAccount> lock(UUID id) {
        return jdbc.query("SELECT * FROM c2c_payment_accounts WHERE account_id=? FOR UPDATE", this::read, id).stream().findFirst();
    }

    public void insert(UUID id, long userId, String fiat, String method, String encrypted, String masked) {
        jdbc.update("""
                INSERT INTO c2c_payment_accounts(account_id,user_id,fiat,method_code,details_ciphertext,masked_label)
                VALUES (?,?,?,?,?,?)
                """, id, userId, fiat, method, encrypted, masked);
    }

    public int replaceDetails(UUID id, long userId, long revision, String fiat, String method, String encrypted, String masked) {
        return jdbc.update("""
                UPDATE c2c_payment_accounts SET fiat=?,method_code=?,details_ciphertext=?,masked_label=?,
                    status='PENDING',reviewed_by=NULL,reviewed_at=NULL,review_reason=NULL,revision=revision+1,updated_at=now()
                WHERE account_id=? AND user_id=? AND revision=?
                """, fiat, method, encrypted, masked, id, userId, revision);
    }

    public int disable(UUID id, long userId, long revision) {
        return jdbc.update("""
                UPDATE c2c_payment_accounts SET status='DISABLED',revision=revision+1,updated_at=now()
                WHERE account_id=? AND user_id=? AND revision=?
                """, id, userId, revision);
    }

    public void review(UUID id, String status, String reason, long reviewer) {
        jdbc.update("""
                UPDATE c2c_payment_accounts SET status=?,review_reason=?,reviewed_by=?,reviewed_at=now(),
                    revision=revision+1,updated_at=now() WHERE account_id=?
                """, status, reason, reviewer, id);
    }

    private C2cPaymentAccount read(ResultSet r, int n) throws SQLException {
        return new C2cPaymentAccount(r.getObject("account_id", UUID.class), r.getLong("user_id"), r.getString("fiat"),
                r.getString("method_code"), r.getString("details_ciphertext"), r.getString("masked_label"),
                r.getString("status"), r.getLong("revision"));
    }

    public Optional<Long> owner(UUID id) {
        return jdbc.query("SELECT user_id FROM c2c_payment_accounts WHERE account_id=?",
                (r, n) -> r.getLong(1), id).stream().findFirst();
    }

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
    public void requireReviewByMethod(String code) {
        jdbc.update("""
                UPDATE c2c_payment_accounts SET status='PENDING',revision=revision+1,reviewed_by=NULL,reviewed_at=NULL,
                    review_reason=NULL,updated_at=now() WHERE method_code=? AND status='APPROVED'
                """,code);
    }

}
