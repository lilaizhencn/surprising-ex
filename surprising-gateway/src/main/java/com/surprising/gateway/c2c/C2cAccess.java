package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.model.C2cMarket;
import com.surprising.gateway.c2c.repository.C2cProfileRepository;
import com.surprising.gateway.c2c.repository.C2cSecurityGrantRepository;
import com.surprising.gateway.c2c.utils.C2cDigests;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.GatewayUserRepository;
import com.surprising.gateway.provider.auth.SensitiveActionVerificationService;
import java.time.Instant;
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
    private final C2cProfileRepository profiles;
    private final C2cSecurityGrantRepository grants;
    private final GatewayUserRepository users;
    private final TransactionTemplate verification;
    public C2cAccess(AuthService auth, ComplianceService compliance,
                     SensitiveActionVerificationService security, C2cProfileRepository profiles, C2cSecurityGrantRepository grants, GatewayUserRepository users,
                     PlatformTransactionManager transactions) {
        this.auth = auth; this.compliance = compliance; this.security = security; this.profiles = profiles; this.grants = grants; this.users = users;
        verification = new TransactionTemplate(transactions);
        verification.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    JwtPrincipal user(String authorization) {
        try { return auth.authenticateBearer(authorization); }
        catch (IllegalArgumentException failure) {
            throw new C2cProblem(org.springframework.http.HttpStatus.UNAUTHORIZED, "C2C_AUTH_REQUIRED", "请先登录有效账户");
        } catch (IllegalStateException failure) { throw C2cProblem.forbidden("账户当前不可使用 C2C"); }
    }

    void requireTrading(JwtPrincipal user, C2cMarket market) { requireTrading(user.userId(),user.status(),market); }
    private void requireTrading(long userId, String status, C2cMarket market) {
        if (!"NORMAL".equals(status)) throw new IllegalStateException("账户当前不能进行 C2C 交易");
        compliance.requireWithdrawalEligibility(userId);
        if (market.requireKyc()) {
            var kyc = compliance.kyc(userId);
            if (kyc == null || !"VERIFIED".equals(kyc.status())
                    || (kyc.expiresAt() != null && !kyc.expiresAt().isAfter(Instant.now())))
                throw new IllegalStateException("请先完成有效身份认证");
        }
    }

    void requireCounterparty(long userId, C2cMarket market) {
        var user=users.find(userId).orElseThrow(()->new IllegalStateException("交易对手不可用"));
        requireTrading(userId,user.status(),market);
    }

    void verifyFunds(JwtPrincipal user, String authorization, String emailCode, String totpCode) {
        String tokenHash = C2cDigests.sha256(authorization);
        boolean accepted = Boolean.TRUE.equals(verification.execute(transaction -> {
            // 与验证设置修改串行，防止旧 TTL 在修改并清空凭据后重新插入长效凭据。
            int ttl = profiles.verificationMinutesWithShareLock(user.userId()).orElse(0);
            if (ttl > 0 && grants.active(user.userId(), tokenHash)) return true;
            if (!security.verify(user.userId(), "WITHDRAWAL", emailCode, totpCode, Instant.now())) return false;
            if (ttl > 0) grants.save(user.userId(), tokenHash, Instant.now().plusSeconds(ttl * 60L));
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

}
