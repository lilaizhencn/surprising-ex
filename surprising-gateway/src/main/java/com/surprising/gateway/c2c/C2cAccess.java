package com.surprising.gateway.c2c;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.auth.GatewayUserRepository;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.SensitiveActionVerificationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 本人身份、KYC、风险保护期和当前登录会话的资金验证共同构成 C2C 准入。 */
@Service
public class C2cAccess {
    private final AuthService auth;
    private final ComplianceService compliance;
    private final SensitiveActionVerificationService security;
    private final JdbcTemplate jdbc;
    private final GatewayUserRepository users;
    private final TransactionTemplate verification;
    public C2cAccess(AuthService auth, ComplianceService compliance,
                     SensitiveActionVerificationService security, JdbcTemplate jdbc, GatewayUserRepository users,
                     PlatformTransactionManager transactions) {
        this.auth = auth; this.compliance = compliance; this.security = security; this.jdbc = jdbc; this.users = users;
        verification = new TransactionTemplate(transactions);
        verification.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    JwtPrincipal user(String authorization) {
        try { return auth.authenticateBearer(authorization); }
        catch (IllegalArgumentException failure) {
            throw new C2cProblem(org.springframework.http.HttpStatus.UNAUTHORIZED, "C2C_AUTH_REQUIRED", "请先登录有效账户");
        } catch (IllegalStateException failure) { throw C2cProblem.forbidden("账户当前不可使用 C2C"); }
    }

    void requireTrading(JwtPrincipal user, C2cMarkets.Market market) { requireTrading(user.userId(),user.status(),market); }
    private void requireTrading(long userId, String status, C2cMarkets.Market market) {
        if (!"NORMAL".equals(status)) throw new IllegalStateException("账户当前不能进行 C2C 交易");
        compliance.requireWithdrawalEligibility(userId);
        if (market.requireKyc()) {
            var kyc = compliance.kyc(userId);
            if (kyc == null || !"VERIFIED".equals(kyc.status())
                    || (kyc.expiresAt() != null && !kyc.expiresAt().isAfter(Instant.now())))
                throw new IllegalStateException("请先完成有效身份认证");
        }
    }

    void requireCounterparty(long userId, C2cMarkets.Market market) {
        var user=users.find(userId).orElseThrow(()->new IllegalStateException("交易对手不可用"));
        requireTrading(userId,user.status(),market);
    }

    void verifyFunds(JwtPrincipal user, String authorization, String emailCode, String totpCode) {
        String tokenHash = digest(authorization);
        boolean accepted = Boolean.TRUE.equals(verification.execute(transaction -> {
            // 与验证设置修改串行，防止旧 TTL 在修改并清空凭据后重新插入长效凭据。
            var minutes = jdbc.query("SELECT verification_minutes FROM c2c_profiles WHERE user_id=? FOR SHARE",
                    (r, n) -> r.getInt(1), user.userId());
            int ttl = minutes.isEmpty() ? 0 : minutes.getFirst();
            if (ttl > 0 && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM c2c_security_grants WHERE user_id=? AND token_hash=? AND expires_at>now())
                    """, Boolean.class, user.userId(), tokenHash))) return true;
            if (!security.verify(user.userId(), "WITHDRAWAL", emailCode, totpCode, Instant.now())) return false;
            if (ttl > 0) jdbc.update("""
                    INSERT INTO c2c_security_grants(user_id,token_hash,expires_at) VALUES (?,?,?)
                    ON CONFLICT(user_id,token_hash) DO UPDATE SET expires_at=EXCLUDED.expires_at
                    """, user.userId(), tokenHash, java.sql.Timestamp.from(Instant.now().plusSeconds(ttl * 60L)));
            return true;
        }));
        // 失败次数与验证码消费独立提交，不能被后续业务事务的回滚撤销。
        if (!accepted) throw new IllegalStateException("请完成邮箱与已绑定验证器的安全验证");
    }

    void verifySettings(JwtPrincipal user, String emailCode, String totpCode) {
        boolean accepted = Boolean.TRUE.equals(verification.execute(transaction ->
                security.verify(user.userId(), "SECURITY_SETTINGS", emailCode, totpCode, Instant.now())));
        if (!accepted) throw new IllegalStateException("请完成本次安全设置验证");
    }

    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
