package com.surprising.gateway.provider.config;

import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * Security, authentication, session, and admin-approval settings for the gateway.
 */
@Getter
public class GatewaySecurityProperties {

    @Setter
    private List<String> corsAllowedOrigins = List.of("http://localhost:5173", "http://127.0.0.1:5173",
            "http://localhost:5174", "http://127.0.0.1:5174", "http://localhost:4173", "http://127.0.0.1:4173",
            "https://ex.tokdou.com");
    @Setter
    private String userIdHeader = "X-User-Id";
    @Setter
    private boolean requireIdentityForPrivateRoutes = true;
    private List<String> adminRoles = List.of("SUPPORT", "ADMIN", "SUPER_ADMIN");
    private List<String> adminIpAllowlist = List.of();
    private List<String> trustedProxyIpAllowlist = List.of();
    @Setter
    private boolean requireApprovalForHighRiskAdminWrites = true;
    private String adminApprovalHeader = "X-Admin-Approval-Id";
    private Duration adminApprovalTtl = Duration.ofMinutes(30);
    @Setter
    private boolean requireAdminMfa = false;
    @Setter
    private boolean phoneRegistrationEnabled = false;
    @Setter
    private boolean requireEmailVerification = true;
    private String resendApiKey = "";
    private String resendFrom = "";
    private String resendBaseUrl = "https://api.resend.com";
    private String verificationCodePepper = "local-dev-verification-pepper-change-me";
    @Setter
    private boolean simulatedVerificationCodesEnabled = false;
    private Duration verificationCodeTtl = Duration.ofMinutes(10);
    private String mfaSecretEncryptionKey = "";
    @Setter
    private String issuer = "surprising-ex-gateway";
    @Setter
    private String jwtSecret = "local-dev-change-me-surprising-ex-gateway-secret-2026";
    /** 管理员登录会话的滑动有效期：最后一次请求之后保留多久，且不超过主认证后的该时长。 */
    @Setter
    private Duration adminSessionTtl = Duration.ofHours(8);
    /** 登录令牌（JWT）自身的最长寿命；会话是否有效由 Valkey 决定，令牌到期只是兜底。 */
    @Setter
    private Duration sessionTokenMaxLifetime = Duration.ofDays(365);
    /** 浏览器会话 Cookie 的 Domain，例如 .tokdou.com；留空表示只对当前主机生效（本地开发）。 */
    @Setter
    private String browserCookieDomain = "";
    @Setter
    private boolean browserCookieSecure = true;
    @Setter
    private List<String> adminBrowserAllowedOrigins = List.of("https://ex-api.tokdou.com");
    /** 普通用户登录会话的滑动有效期：最后一次请求之后保留多久。 */
    @Setter
    private Duration sessionTtl = Duration.ofDays(7);
    /** 同一会话在 Valkey 里续期的最小间隔，避免每个请求都写 Valkey。 */
    @Setter
    private Duration sessionRenewInterval = Duration.ofMinutes(10);
    /** 网关进程内缓存登录态的时间；其它网关的变更最晚这么久后生效（失效广播丢失时）。 */
    @Setter
    private Duration authLocalCacheTtl = Duration.ofSeconds(4);
    /** Valkey 不可用时，进程内已有的登录态还能继续使用多久。 */
    @Setter
    private Duration authLocalStaleTtl = Duration.ofSeconds(60);
    /** 网关启动时同步持久的 API Key、Telegram 绑定和关联用户；绝不恢复登录会话。 */
    @Setter
    private boolean authPersistentSyncOnStart = true;

    public void setAdminRoles(List<String> adminRoles) {
        this.adminRoles = adminRoles == null || adminRoles.isEmpty()
                ? List.of("SUPPORT", "ADMIN", "SUPER_ADMIN")
                : List.copyOf(adminRoles);
    }

    public void setAdminIpAllowlist(List<String> adminIpAllowlist) {
        this.adminIpAllowlist = adminIpAllowlist == null ? List.of() : List.copyOf(adminIpAllowlist);
    }

    public void setTrustedProxyIpAllowlist(List<String> trustedProxyIpAllowlist) {
        this.trustedProxyIpAllowlist = trustedProxyIpAllowlist == null
                ? List.of() : List.copyOf(trustedProxyIpAllowlist);
    }

    public void setAdminApprovalHeader(String adminApprovalHeader) {
        this.adminApprovalHeader = adminApprovalHeader == null || adminApprovalHeader.isBlank()
                ? "X-Admin-Approval-Id"
                : adminApprovalHeader;
    }

    public void setAdminApprovalTtl(Duration adminApprovalTtl) {
        this.adminApprovalTtl = adminApprovalTtl == null || adminApprovalTtl.isZero() || adminApprovalTtl.isNegative()
                ? Duration.ofMinutes(30)
                : adminApprovalTtl;
    }

    public void setResendApiKey(String resendApiKey) {
        this.resendApiKey = resendApiKey == null ? "" : resendApiKey;
    }

    public void setResendFrom(String resendFrom) {
        this.resendFrom = resendFrom == null ? "" : resendFrom;
    }

    public void setResendBaseUrl(String resendBaseUrl) {
        if (resendBaseUrl == null || resendBaseUrl.isBlank()) {
            this.resendBaseUrl = "https://api.resend.com";
        } else {
            this.resendBaseUrl = resendBaseUrl.endsWith("/")
                    ? resendBaseUrl.substring(0, resendBaseUrl.length() - 1) : resendBaseUrl;
        }
    }

    public void setVerificationCodePepper(String verificationCodePepper) {
        this.verificationCodePepper = verificationCodePepper == null ? "" : verificationCodePepper;
    }

    public void setVerificationCodeTtl(Duration verificationCodeTtl) {
        this.verificationCodeTtl = verificationCodeTtl == null || verificationCodeTtl.isNegative()
                || verificationCodeTtl.isZero() ? Duration.ofMinutes(10) : verificationCodeTtl;
    }

    public void setMfaSecretEncryptionKey(String mfaSecretEncryptionKey) {
        this.mfaSecretEncryptionKey = mfaSecretEncryptionKey == null ? "" : mfaSecretEncryptionKey;
    }
}
