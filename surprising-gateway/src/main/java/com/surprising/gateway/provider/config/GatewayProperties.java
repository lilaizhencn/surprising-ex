package com.surprising.gateway.provider.config;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.context.EnvironmentAware;

@ConfigurationProperties(prefix = "surprising.gateway")
public class GatewayProperties implements EnvironmentAware {

    private Environment environment = new StandardEnvironment();
    @Getter
    private String deploymentProfile = "local";
    @Getter
    @Setter
    private Security security = new Security();
    @Getter
    private CustodyWallet custodyWallet = new CustodyWallet();
    @Getter
    private Withdrawal withdrawal = new Withdrawal();
    @Getter
    private ProductTransfer productTransfer = new ProductTransfer();
    @Getter
    private KycDocuments kycDocuments = new KycDocuments();
    @Getter
    private Kyc kyc = new Kyc();
    @Getter
    private BinanceApi binanceApi = new BinanceApi();
    @Getter
    @Setter
    private HttpClient httpClient = new HttpClient();
    @Getter
    @Setter
    private Map<String, BackendRoute> routes = defaultRoutes();
    @Getter
    @Setter
    private Map<String, BackendRoute> adminRoutes = defaultAdminRoutes();

    @PostConstruct
    void validateConfiguration() {
        validateProductionSecurityConfiguration();
    }

    void validateProductionSecurityConfiguration() {
        boolean springProductionProfile = environment.acceptsProfiles(Profiles.of("production"));
        boolean configuredProductionProfile = "production".equalsIgnoreCase(deploymentProfile);
        if (!springProductionProfile && !configuredProductionProfile) {
            return;
        }
        List<String> failures = new ArrayList<>();
        if (springProductionProfile && !configuredProductionProfile) {
            failures.add("deployment-profile must remain production when the production Spring profile is active");
        }
        Security configuredSecurity = security == null ? new Security() : security;
        if (!configuredSecurity.isRequireIdentityForPrivateRoutes()) {
            failures.add("security.require-identity-for-private-routes must be true");
        }
        if (configuredSecurity.getAdminBrowserAllowedOrigins() == null || configuredSecurity.getAdminBrowserAllowedOrigins().isEmpty())
            failures.add("security.admin-browser-allowed-origins must not be empty");
        else for (String origin : configuredSecurity.getAdminBrowserAllowedOrigins()) {
            requireHttpsUrl(failures, "security.admin-browser-allowed-origins", origin);
            if (origin.contains("*") || origin.endsWith("/")) failures.add("security.admin-browser-allowed-origins must contain exact origins");
        }
        if (!configuredSecurity.isBrowserCookieSecure()) failures.add("security.browser-cookie-secure must be true");
        requireTokenTtl(failures, "security.session-ttl", configuredSecurity.getSessionTtl(), Duration.ofDays(30));
        requireTokenTtl(failures, "security.admin-session-ttl", configuredSecurity.getAdminSessionTtl(), Duration.ofHours(8));
        requireTokenTtl(failures, "security.session-token-max-lifetime", configuredSecurity.getSessionTokenMaxLifetime(), Duration.ofDays(400));
        if (!configuredSecurity.isRequireAdminMfa()) {
            failures.add("security.require-admin-mfa must be true");
        }
        validateIpAllowlist(failures, "security.admin-ip-allowlist", configuredSecurity.getAdminIpAllowlist());
        validateIpAllowlist(failures, "security.trusted-proxy-ip-allowlist",
                configuredSecurity.getTrustedProxyIpAllowlist());
        requireProductionSecret(failures, "security.jwt-secret", configuredSecurity.getJwtSecret(), 32,
                "local-dev-change-me-surprising-ex-gateway-secret-2026");
        requireProductionSecret(failures, "security.verification-code-pepper",
                configuredSecurity.getVerificationCodePepper(), 32,
                "local-dev-verification-pepper-change-me");
        requireProductionSecret(failures, "security.mfa-secret-encryption-key",
                configuredSecurity.getMfaSecretEncryptionKey(), 32, null);
        if (configuredSecurity.isRequireEmailVerification()) {
            requireNonBlank(failures, "security.resend-api-key", configuredSecurity.getResendApiKey());
            requireNonBlank(failures, "security.resend-from", configuredSecurity.getResendFrom());
            requireHttpsUrl(failures, "security.resend-base-url", configuredSecurity.getResendBaseUrl());
        }

        CustodyWallet wallet = custodyWallet == null ? new CustodyWallet() : custodyWallet;
        if (!wallet.isEnabled()) {
            failures.add("custody-wallet.enabled must be true");
        }
        requireHttpsUrl(failures, "custody-wallet.base-url", wallet.getBaseUrl());
        requireNonBlank(failures, "custody-wallet.api-key", wallet.getApiKey());
        requireNonBlank(failures, "custody-wallet.api-secret", wallet.getApiSecret());
        requireNonBlank(failures, "custody-wallet.webhook-secret", wallet.getWebhookSecret());
        BackendRoute walletAdmin = adminRoutes == null ? null : adminRoutes.get("wallet-admin");
        if (walletAdmin != null && !walletAdmin.hasBasicAuth()) {
            failures.add("admin-routes.wallet-admin.basic-auth must be configured");
        }
        Withdrawal configuredWithdrawal = withdrawal == null ? new Withdrawal() : withdrawal;
        if (configuredWithdrawal.getSingleApprovalThresholdUsdt() == null
                || configuredWithdrawal.getSingleApprovalThresholdUsdt().signum() <= 0) {
            failures.add("withdrawal.single-approval-threshold-usdt must be positive");
        }
        if (configuredWithdrawal.getDailyLimitUsdt() == null
                || configuredWithdrawal.getDailyLimitUsdt().signum() <= 0) {
            failures.add("withdrawal.daily-limit-usdt must be positive");
        }
        if (configuredWithdrawal.getFailureReconciliationDelay() == null
                || configuredWithdrawal.getFailureReconciliationDelay().isNegative()
                || configuredWithdrawal.getFailureReconciliationDelay().isZero()) {
            failures.add("withdrawal.failure-reconciliation-delay must be positive");
        }

        KycDocuments documents = kycDocuments == null ? new KycDocuments() : kycDocuments;
        if (!documents.isEnabled()) {
            failures.add("kyc-documents.enabled must be true");
        }
        if (!"s3".equalsIgnoreCase(documents.getType())) {
            failures.add("kyc-documents.type must be s3");
        }
        requireNonBlank(failures, "kyc-documents.endpoint", documents.getEndpoint());
        requireNonBlank(failures, "kyc-documents.bucket", documents.getBucket());
        requireNonBlank(failures, "kyc-documents.region", documents.getRegion());
        requireNonBlank(failures, "kyc-documents.access-key", documents.getAccessKey());
        requireNonBlank(failures, "kyc-documents.secret-key", documents.getSecretKey());

        Kyc configuredKyc = kyc == null ? new Kyc() : kyc;
        if (configuredKyc.isSimulationEnabled()) {
            failures.add("kyc.simulation-enabled must be false in production");
        }
        String provider = configuredKyc.getProvider();
        if (!List.of("SUMSUB", "VERIFF", "SELF").contains(provider)) {
            failures.add("kyc.provider must be SUMSUB, VERIFF, or SELF");
        } else if ("SUMSUB".equals(provider)) {
            requireNonBlank(failures, "kyc.sumsub.app-token", configuredKyc.getSumsub().getAppToken());
            requireProductionSecret(failures, "kyc.sumsub.secret-key", configuredKyc.getSumsub().getSecretKey(), 32, null);
            requireProductionSecret(failures, "kyc.sumsub.webhook-secret", configuredKyc.getSumsub().getWebhookSecret(), 32, null);
            if (configuredKyc.getSumsub().getLevelNames().values().stream().noneMatch(value -> value != null && !value.isBlank())) {
                failures.add("kyc.sumsub.level-names requires at least one configured verification flow");
            }
            requireHttpsUrl(failures, "kyc.sumsub.base-url", configuredKyc.getSumsub().getBaseUrl());
        } else if ("VERIFF".equals(provider)) {
            requireNonBlank(failures, "kyc.veriff.api-key", configuredKyc.getVeriff().getApiKey());
            requireProductionSecret(failures, "kyc.veriff.shared-secret", configuredKyc.getVeriff().getSharedSecret(), 32, null);
            requireHttpsUrl(failures, "kyc.veriff.base-url", configuredKyc.getVeriff().getBaseUrl());
            if (configuredKyc.getVeriff().getCallbackUrl() != null && !configuredKyc.getVeriff().getCallbackUrl().isBlank()) {
                requireHttpsUrl(failures, "kyc.veriff.callback-url", configuredKyc.getVeriff().getCallbackUrl());
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException("production gateway security configuration is invalid: "
                    + String.join("; ", failures));
        }
    }

    private static void requireNonBlank(List<String> failures, String name, String value) {
        if (value == null || value.isBlank()) {
            failures.add(name + " must be configured");
        }
    }

    private static void requireProductionSecret(List<String> failures, String name, String value,
                                                int minimumLength, String forbiddenValue) {
        if (value == null || value.isBlank() || value.length() < minimumLength
                || (forbiddenValue != null && forbiddenValue.equals(value))) {
            failures.add(name + " must be a non-default secret of at least " + minimumLength + " characters");
        }
    }

    private static void validateIpAllowlist(List<String> failures, String name, List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            failures.add(name + " must not be empty");
            return;
        }
        if (rules.stream().anyMatch(rule -> rule == null || rule.isBlank())) {
            failures.add(name + " must not contain blank rules");
        }
        if (rules.stream().anyMatch(rule -> rule != null
                && ("0.0.0.0/0".equals(rule.trim()) || "::/0".equals(rule.trim())))) {
            failures.add(name + " must not allow all addresses");
        }
    }

    private static void requireHttpsUrl(List<String> failures, String name, String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                failures.add(name + " must be an HTTPS URL with a host");
            }
        } catch (IllegalArgumentException ex) {
            failures.add(name + " must be an HTTPS URL with a host");
        }
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment == null ? new StandardEnvironment() : environment;
    }

    public void setDeploymentProfile(String deploymentProfile) {
        this.deploymentProfile = deploymentProfile == null || deploymentProfile.isBlank()
                ? "local" : deploymentProfile.trim();
    }

    public void setBinanceApi(BinanceApi binanceApi) {
        this.binanceApi = binanceApi == null ? new BinanceApi() : binanceApi;
    }

    private static void requireTokenTtl(List<String> failures, String name, Duration ttl, Duration maximum) {
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(maximum) > 0)
            failures.add(name + " must be positive and no longer than " + maximum);
    }

    public void setCustodyWallet(CustodyWallet custodyWallet) {
        this.custodyWallet = custodyWallet == null ? new CustodyWallet() : custodyWallet;
    }

    public void setWithdrawal(Withdrawal withdrawal) {
        this.withdrawal = withdrawal == null ? new Withdrawal() : withdrawal;
    }

    public void setProductTransfer(ProductTransfer productTransfer) {
        this.productTransfer = productTransfer == null ? new ProductTransfer() : productTransfer;
    }

    public void setKycDocuments(KycDocuments kycDocuments) {
        this.kycDocuments = kycDocuments == null ? new KycDocuments() : kycDocuments;
    }

    public void setKyc(Kyc kyc) { this.kyc = kyc == null ? new Kyc() : kyc; }

    private static Map<String, BackendRoute> defaultRoutes() {
        Map<String, BackendRoute> routes = new LinkedHashMap<>();
        routes.put("instrument", new BackendRoute("local:", "/api/v1/instruments", false));
        routes.put("trading", new BackendRoute("local:", "/api/v1/trading/orders", true));
        routes.put("trading-leverage", new BackendRoute("local:", "/api/v1/trading/leverage", true));
        routes.put("trading-fees", new BackendRoute("local:", "/api/v1/trading/fees", true));
        routes.put("trading-market", new BackendRoute("local:", "/api/v1/trading/market", false));
        routes.put("trading-trigger", new BackendRoute("local:", "/api/v1/trading/trigger-orders", true));
        routes.put("account", new BackendRoute("local:", "/api/v1/accounts", true));
        routes.put("market-maker", new BackendRoute("http://localhost:9096", "/api/v1/market-maker", true));
        return routes;
    }

    private static Map<String, BackendRoute> defaultAdminRoutes() {
        Map<String, BackendRoute> routes = new LinkedHashMap<>();
        routes.put("instrument", new BackendRoute("local:", "/api/v1/instruments", true));
        routes.put("instrument-admin", new BackendRoute("local:", "/api/v1/instruments/admin", true));
        routes.put("trading", new BackendRoute("local:", "/api/v1/admin/trading/orders", true));
        routes.put("trading-orders", new BackendRoute("local:", "/api/v1/admin/trading/orders", true));
        routes.put("trading-leverage", new BackendRoute("local:", "/api/v1/admin/trading/leverage", true));
        routes.put("trading-fees", new BackendRoute("local:", "/api/v1/admin/trading/fees", true));
        routes.put("trading-market", new BackendRoute("local:", "/api/v1/trading/market", true));
        routes.put("trading-trigger", new BackendRoute("local:", "/api/v1/admin/trading/trigger-orders", true));
        routes.put("account", new BackendRoute("local:", "/api/v1/admin/accounts", true));
        routes.put("account-public", new BackendRoute("local:", "/api/v1/accounts", true));
        routes.put("market-maker", new BackendRoute("http://localhost:9096", "/api/v1/admin/market-maker", true));
        return routes;
    }

    public static class Security extends GatewaySecurityProperties {
    }

    public static class CustodyWallet extends GatewayCustodyProperties.CustodyWallet {
    }

    public static class Withdrawal extends GatewayCustodyProperties.Withdrawal {
    }

    public static class ProductTransfer extends GatewayCustodyProperties.ProductTransfer {
    }

    public static class BinanceApi extends GatewayOpenApiProperties.BinanceApi {
    }

    public static class KycDocuments extends GatewayKycProperties.KycDocuments {
    }

    public static class Kyc extends GatewayKycProperties.Kyc {
    }

    public static class Sumsub extends GatewayKycProperties.Sumsub {
    }

    public static class Veriff extends GatewayKycProperties.Veriff {
    }

    public static class SymbolScale extends GatewayOpenApiProperties.SymbolScale {
    }

    @Getter
    @Setter
    public static class HttpClient {
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(5);
    }

    @Getter
    public static class BackendRoute {
        @Setter
        private String baseUrl;
        @Setter
        private String targetPrefix;
        @Setter
        private boolean privateRoute;
        @Setter
        private String basicAuthUsername;
        @Setter
        private String basicAuthPassword;
        private Map<ProductLine, ProductRoute> productRoutes = new LinkedHashMap<>();

        public BackendRoute() {
        }

        public BackendRoute(String baseUrl, String targetPrefix, boolean privateRoute) {
            this.baseUrl = baseUrl;
            this.targetPrefix = targetPrefix;
            this.privateRoute = privateRoute;
        }

        public boolean hasBasicAuth() {
            return basicAuthUsername != null && !basicAuthUsername.isBlank()
                    && basicAuthPassword != null && !basicAuthPassword.isBlank();
        }

        public void setProductRoutes(Map<ProductLine, ProductRoute> productRoutes) {
            this.productRoutes = productRoutes == null ? Map.of() : Map.copyOf(productRoutes);
        }

        public boolean hasProductRoutes() {
            return productRoutes != null && !productRoutes.isEmpty();
        }

        public BackendRoute resolve(ProductLine productLine) {
            if (productLine == null || !hasProductRoutes()) {
                return this;
            }
            ProductRoute productRoute = productRoutes.get(productLine);
            if (productRoute == null) {
                return null;
            }
            BackendRoute resolved = new BackendRoute(
                    productRoute.getBaseUrl() == null || productRoute.getBaseUrl().isBlank()
                            ? baseUrl
                            : productRoute.getBaseUrl(),
                    productRoute.getTargetPrefix() == null || productRoute.getTargetPrefix().isBlank()
                            ? targetPrefix
                            : productRoute.getTargetPrefix(),
                    privateRoute);
            resolved.setBasicAuthUsername(productRoute.getBasicAuthUsername() == null
                    ? basicAuthUsername
                    : productRoute.getBasicAuthUsername());
            resolved.setBasicAuthPassword(productRoute.getBasicAuthPassword() == null
                    ? basicAuthPassword
                    : productRoute.getBasicAuthPassword());
            return resolved;
        }
    }

    @Getter
    @Setter
    public static class ProductRoute {
        private String baseUrl;
        private String targetPrefix;
        private String basicAuthUsername;
        private String basicAuthPassword;

        public ProductRoute() {
        }

        public ProductRoute(String baseUrl, String targetPrefix) {
            this.baseUrl = baseUrl;
            this.targetPrefix = targetPrefix;
        }
    }
}
