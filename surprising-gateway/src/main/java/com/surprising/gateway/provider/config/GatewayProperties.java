package com.surprising.gateway.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import com.surprising.product.api.ProductTopicNames;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@ConfigurationProperties(prefix = "surprising.gateway")
public class GatewayProperties implements EnvironmentAware {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Environment environment = new StandardEnvironment();
    private String deploymentProfile = "local";
    private Security security = new Security();
    private CustodyWallet custodyWallet = new CustodyWallet();
    private Withdrawal withdrawal = new Withdrawal();
    private ProductTransfer productTransfer = new ProductTransfer();
    private KycDocuments kycDocuments = new KycDocuments();
    private BinanceApi binanceApi = new BinanceApi();
    private HttpClient httpClient = new HttpClient();
    private Observability observability = new Observability();
    private Map<String, BackendRoute> routes = defaultRoutes();
    private Map<String, BackendRoute> adminRoutes = defaultAdminRoutes();

    /** 仅在启用 Kafka 监控时校验其产品线，避免监控配置影响网关启动。 */
    @PostConstruct
    void validateConfiguration() {
        if (observability.kafka.enabled) {
            ProductLineConfiguration.require(observability.kafka.productLine,
                    observability.kafka.productTopicsEnabled, "gateway-admin-monitor");
        }
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
        if (configuredSecurity.isAllowUserIdHeaderFallback()) {
            failures.add("security.allow-user-id-header-fallback must be false");
        }
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
        requireHttpsUrl(failures, "custody-wallet.spot-account-base-url", wallet.getSpotAccountBaseUrl());
        requireProductionSecret(failures, "custody-wallet.spot-account-internal-secret",
                wallet.getSpotAccountInternalSecret(), 32, "local-dev-spot-account-internal-secret-change-me");
        BackendRoute walletAdmin = adminRoutes == null ? null : adminRoutes.get("wallet-admin");
        if (walletAdmin == null || !walletAdmin.hasBasicAuth()) {
            failures.add("admin-routes.wallet-admin.basic-auth must be configured");
        }
        if (wallet.getWithdrawalAddressIds().isEmpty()) {
            failures.add("custody-wallet.withdrawal-address-ids must contain at least one network");
        } else {
            wallet.getWithdrawalAddressIds().forEach((network, addressId) -> {
                if (network == null || network.isBlank()) {
                    failures.add("custody-wallet.withdrawal-address-ids contains a blank network");
                }
                try {
                    java.util.UUID.fromString(addressId);
                } catch (IllegalArgumentException | NullPointerException ex) {
                    failures.add("custody-wallet.withdrawal-address-ids contains an invalid address id");
                }
            });
        }
        if (wallet.getAssetScales().isEmpty()) {
            failures.add("custody-wallet.asset-scales must contain at least one asset");
        } else {
            wallet.getAssetScales().forEach((asset, scale) -> {
                if (asset == null || asset.isBlank()) {
                    failures.add("custody-wallet.asset-scales contains a blank asset");
                }
                if (scale == null || scale < 0L || scale > 18L) {
                    failures.add("custody-wallet.asset-scales contains an invalid scale");
                }
            });
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
        requireHttpsUrl(failures, "withdrawal.valuation-base-url",
                configuredWithdrawal.getValuationBaseUrl());
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

    private static void requireServiceUrl(List<String> failures, String name, String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            if ((!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
                    || uri.getHost() == null) {
                failures.add(name + " must be an HTTP(S) URL with a host");
            }
        } catch (IllegalArgumentException ex) {
            failures.add(name + " must be an HTTP(S) URL with a host");
        }
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment == null ? new StandardEnvironment() : environment;
    }

    public String getDeploymentProfile() {
        return deploymentProfile;
    }

    public void setDeploymentProfile(String deploymentProfile) {
        this.deploymentProfile = deploymentProfile == null || deploymentProfile.isBlank()
                ? "local" : deploymentProfile.trim();
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public CustodyWallet getCustodyWallet() {
        return custodyWallet;
    }

    public BinanceApi getBinanceApi() {
        return binanceApi;
    }

    public void setBinanceApi(BinanceApi binanceApi) {
        this.binanceApi = binanceApi == null ? new BinanceApi() : binanceApi;
    }

    public void setCustodyWallet(CustodyWallet custodyWallet) {
        this.custodyWallet = custodyWallet == null ? new CustodyWallet() : custodyWallet;
    }

    public Withdrawal getWithdrawal() {
        return withdrawal;
    }

    public void setWithdrawal(Withdrawal withdrawal) {
        this.withdrawal = withdrawal == null ? new Withdrawal() : withdrawal;
    }

    public ProductTransfer getProductTransfer() {
        return productTransfer;
    }

    public void setProductTransfer(ProductTransfer productTransfer) {
        this.productTransfer = productTransfer == null ? new ProductTransfer() : productTransfer;
    }

    public KycDocuments getKycDocuments() {
        return kycDocuments;
    }

    public void setKycDocuments(KycDocuments kycDocuments) {
        this.kycDocuments = kycDocuments == null ? new KycDocuments() : kycDocuments;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public Observability getObservability() {
        return observability;
    }

    public void setObservability(Observability observability) {
        this.observability = observability == null ? new Observability() : observability;
    }

    public Map<String, BackendRoute> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, BackendRoute> routes) {
        this.routes = routes;
    }

    public Map<String, BackendRoute> getAdminRoutes() {
        return adminRoutes;
    }

    public void setAdminRoutes(Map<String, BackendRoute> adminRoutes) {
        this.adminRoutes = adminRoutes;
    }

    private static Map<?, ?> readJsonObject(String value) {
        if (value == null || value.isBlank() || value.trim().equals("{}")) {
            return Map.of();
        }
        try {
            Object parsed = JSON.readValue(value, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("configuration JSON must be an object");
            }
            return map;
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("configuration JSON is invalid", ex);
        }
    }

    private static Map<String, String> readStringMap(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        readJsonObject(value).forEach((key, item) -> {
            if (key == null || item == null) {
                throw new IllegalArgumentException("configuration map contains null entry");
            }
            result.put(key.toString(), item.toString());
        });
        return result;
    }

    private static Map<String, Long> readLongMap(String value) {
        Map<String, Long> result = new LinkedHashMap<>();
        readJsonObject(value).forEach((key, item) -> {
            try {
                result.put(key.toString(), Long.valueOf(item.toString()));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("configuration map contains invalid integer", ex);
            }
        });
        return result;
    }

    private static Map<String, BackendRoute> defaultRoutes() {
        Map<String, BackendRoute> routes = new LinkedHashMap<>();
        routes.put("instrument", new BackendRoute("http://localhost:9080", "/api/v1/instruments", false));
        routes.put("candlestick", new BackendRoute("http://localhost:9081", "/api/v1/candlestick", false));
        routes.put("price-index", new BackendRoute("http://localhost:9082", "/api/v1/price/index", false));
        routes.put("price-fx", new BackendRoute("http://localhost:9082", "/api/v1/price/fx", false));
        routes.put("price-mark", new BackendRoute("http://localhost:9083", "/api/v1/price/mark", false));
        routes.put("trading", new BackendRoute("http://localhost:9084", "/api/v1/trading/orders", true));
        routes.put("trading-leverage", new BackendRoute("http://localhost:9084", "/api/v1/trading/leverage", true));
        routes.put("trading-market", new BackendRoute("http://localhost:9085", "/api/v1/trading/market", false));
        routes.put("trading-trades", new BackendRoute("http://localhost:9085", "/api/v1/trading/market", true));
        routes.put("trading-trigger", new BackendRoute("http://localhost:9084", "/api/v1/trading/trigger-orders", true));
        routes.put("account", new BackendRoute("http://localhost:9086", "/api/v1/accounts", true));
        routes.put("risk", new BackendRoute("http://localhost:9088", "/api/v1/risk", true));
        routes.put("liquidation", new BackendRoute("http://localhost:9088", "/api/v1/liquidations", true));
        routes.put("funding", new BackendRoute("http://localhost:9089", "/api/v1/funding", false));
        routes.put("insurance", new BackendRoute("http://localhost:9090", "/api/v1/insurance", true));
        routes.put("adl", new BackendRoute("http://localhost:9091", "/api/v1/adl", true));
        routes.put("market-maker", new BackendRoute("http://localhost:9096", "/api/v1/market-maker", true));
        routes.put("wallet", new BackendRoute("http://localhost:8002", "/wallet/v1", true));
        routes.put("websocket", new BackendRoute("http://localhost:9093", "/ws", false));
        return routes;
    }

    private static Map<String, BackendRoute> defaultAdminRoutes() {
        Map<String, BackendRoute> routes = new LinkedHashMap<>();
        routes.put("instrument", new BackendRoute("http://localhost:9080", "/api/v1/instruments", true));
        routes.put("instrument-admin", new BackendRoute("http://localhost:9080", "/api/v1/instruments/admin", true));
        routes.put("candlestick", new BackendRoute("http://localhost:9081", "/api/v1/candlestick", true));
        routes.put("price-index", new BackendRoute("http://localhost:9082", "/api/v1/price/index", true));
        routes.put("price-fx", new BackendRoute("http://localhost:9082", "/api/v1/price/fx", true));
        routes.put("price-mark", new BackendRoute("http://localhost:9083", "/api/v1/price/mark", true));
        routes.put("trading", new BackendRoute("http://localhost:9084", "/api/v1/admin/trading/orders", true));
        routes.put("trading-orders", new BackendRoute("http://localhost:9084", "/api/v1/admin/trading/orders", true));
        routes.put("trading-fees", new BackendRoute("http://localhost:9084", "/api/v1/admin/trading/fees", true));
        routes.put("trading-market", new BackendRoute("http://localhost:9085", "/api/v1/trading/market", true));
        routes.put("trading-trigger", new BackendRoute("http://localhost:9084", "/api/v1/admin/trading/trigger-orders", true));
        routes.put("account", new BackendRoute("http://localhost:9086", "/api/v1/admin/accounts", true));
        routes.put("account-public", new BackendRoute("http://localhost:9086", "/api/v1/accounts", true));
        routes.put("risk", new BackendRoute("http://localhost:9088", "/api/v1/risk", true));
        routes.put("risk-admin", new BackendRoute("http://localhost:9088", "/api/v1/admin/risk", true));
        routes.put("liquidation", new BackendRoute("http://localhost:9088", "/api/v1/liquidations", true));
        routes.put("liquidation-admin", new BackendRoute("http://localhost:9088", "/api/v1/admin/liquidations", true));
        routes.put("funding", new BackendRoute("http://localhost:9089", "/api/v1/funding", true));
        routes.put("insurance", new BackendRoute("http://localhost:9090", "/api/v1/insurance", true));
        routes.put("insurance-admin", new BackendRoute("http://localhost:9090", "/api/v1/insurance/admin", true));
        routes.put("adl", new BackendRoute("http://localhost:9091", "/api/v1/adl", true));
        routes.put("market-maker", new BackendRoute("http://localhost:9096", "/api/v1/admin/market-maker", true));
        routes.put("wallet", new BackendRoute("http://localhost:8002", "/wallet/v1", true));
        routes.put("wallet-admin", walletAdminRoute());
        routes.put("websocket-admin", new BackendRoute("http://localhost:9093", "/api/v1/admin/websocket", true));
        return routes;
    }

    private static BackendRoute walletAdminRoute() {
        BackendRoute route = new BackendRoute("http://localhost:8002", "/wallet/v1/admin", true);
        route.setBasicAuthUsername(System.getenv().getOrDefault("SW_WALLET_ADMIN_USERNAME", ""));
        route.setBasicAuthPassword(System.getenv().getOrDefault("SW_WALLET_ADMIN_PASSWORD", ""));
        return route;
    }

    public static class Security {
        private String userIdHeader = "X-User-Id";
        private boolean requireIdentityForPrivateRoutes = true;
        private boolean allowUserIdHeaderFallback = true;
        private List<String> adminRoles = List.of("SUPPORT", "ADMIN", "SUPER_ADMIN");
        private List<String> adminIpAllowlist = List.of();
        private List<String> trustedProxyIpAllowlist = List.of();
        private boolean requireApprovalForHighRiskAdminWrites = true;
        private String adminApprovalHeader = "X-Admin-Approval-Id";
        private Duration adminApprovalTtl = Duration.ofMinutes(30);
        private boolean requireAdminMfa = false;
        private boolean phoneRegistrationEnabled = false;
        private boolean requireEmailVerification = true;
        private String resendApiKey = "";
        private String resendFrom = "";
        private String resendBaseUrl = "https://api.resend.com";
        private String verificationCodePepper = "local-dev-verification-pepper-change-me";
        private Duration verificationCodeTtl = Duration.ofMinutes(10);
        private String mfaSecretEncryptionKey = "";
        private String issuer = "surprising-ex-gateway";
        private String jwtSecret = "local-dev-change-me-surprising-ex-gateway-secret-2026";
        private Duration accessTokenTtl = Duration.ofMinutes(30);
        private Duration refreshTokenTtl = Duration.ofDays(30);

        public String getUserIdHeader() {
            return userIdHeader;
        }

        public void setUserIdHeader(String userIdHeader) {
            this.userIdHeader = userIdHeader;
        }

        public boolean isRequireIdentityForPrivateRoutes() {
            return requireIdentityForPrivateRoutes;
        }

        public void setRequireIdentityForPrivateRoutes(boolean requireIdentityForPrivateRoutes) {
            this.requireIdentityForPrivateRoutes = requireIdentityForPrivateRoutes;
        }

        public boolean isAllowUserIdHeaderFallback() {
            return allowUserIdHeaderFallback;
        }

        public void setAllowUserIdHeaderFallback(boolean allowUserIdHeaderFallback) {
            this.allowUserIdHeaderFallback = allowUserIdHeaderFallback;
        }

        public List<String> getAdminRoles() {
            return adminRoles;
        }

        public void setAdminRoles(List<String> adminRoles) {
            this.adminRoles = adminRoles == null || adminRoles.isEmpty()
                    ? List.of("SUPPORT", "ADMIN", "SUPER_ADMIN")
                    : List.copyOf(adminRoles);
        }

        public List<String> getAdminIpAllowlist() {
            return adminIpAllowlist;
        }

        public void setAdminIpAllowlist(List<String> adminIpAllowlist) {
            this.adminIpAllowlist = adminIpAllowlist == null ? List.of() : List.copyOf(adminIpAllowlist);
        }

        public List<String> getTrustedProxyIpAllowlist() {
            return trustedProxyIpAllowlist;
        }

        public void setTrustedProxyIpAllowlist(List<String> trustedProxyIpAllowlist) {
            this.trustedProxyIpAllowlist = trustedProxyIpAllowlist == null
                    ? List.of() : List.copyOf(trustedProxyIpAllowlist);
        }

        public boolean isRequireApprovalForHighRiskAdminWrites() {
            return requireApprovalForHighRiskAdminWrites;
        }

        public void setRequireApprovalForHighRiskAdminWrites(boolean requireApprovalForHighRiskAdminWrites) {
            this.requireApprovalForHighRiskAdminWrites = requireApprovalForHighRiskAdminWrites;
        }

        public String getAdminApprovalHeader() {
            return adminApprovalHeader;
        }

        public void setAdminApprovalHeader(String adminApprovalHeader) {
            this.adminApprovalHeader = adminApprovalHeader == null || adminApprovalHeader.isBlank()
                    ? "X-Admin-Approval-Id"
                    : adminApprovalHeader;
        }

        public Duration getAdminApprovalTtl() {
            return adminApprovalTtl;
        }

        public void setAdminApprovalTtl(Duration adminApprovalTtl) {
            this.adminApprovalTtl = adminApprovalTtl == null || adminApprovalTtl.isZero() || adminApprovalTtl.isNegative()
                    ? Duration.ofMinutes(30)
                    : adminApprovalTtl;
        }

        public boolean isRequireAdminMfa() {
            return requireAdminMfa;
        }

        public void setRequireAdminMfa(boolean requireAdminMfa) {
            this.requireAdminMfa = requireAdminMfa;
        }

        public boolean isPhoneRegistrationEnabled() {
            return phoneRegistrationEnabled;
        }

        public void setPhoneRegistrationEnabled(boolean phoneRegistrationEnabled) {
            this.phoneRegistrationEnabled = phoneRegistrationEnabled;
        }

        public boolean isRequireEmailVerification() {
            return requireEmailVerification;
        }

        public void setRequireEmailVerification(boolean requireEmailVerification) {
            this.requireEmailVerification = requireEmailVerification;
        }

        public String getResendApiKey() {
            return resendApiKey;
        }

        public void setResendApiKey(String resendApiKey) {
            this.resendApiKey = resendApiKey == null ? "" : resendApiKey;
        }

        public String getResendFrom() {
            return resendFrom;
        }

        public void setResendFrom(String resendFrom) {
            this.resendFrom = resendFrom == null ? "" : resendFrom;
        }

        public String getResendBaseUrl() {
            return resendBaseUrl;
        }

        public void setResendBaseUrl(String resendBaseUrl) {
            this.resendBaseUrl = resendBaseUrl == null || resendBaseUrl.isBlank()
                    ? "https://api.resend.com" : resendBaseUrl.replaceAll("/$", "");
        }

        public String getVerificationCodePepper() {
            return verificationCodePepper;
        }

        public void setVerificationCodePepper(String verificationCodePepper) {
            this.verificationCodePepper = verificationCodePepper == null ? "" : verificationCodePepper;
        }

        public Duration getVerificationCodeTtl() {
            return verificationCodeTtl;
        }

        public void setVerificationCodeTtl(Duration verificationCodeTtl) {
            this.verificationCodeTtl = verificationCodeTtl == null || verificationCodeTtl.isNegative()
                    || verificationCodeTtl.isZero() ? Duration.ofMinutes(10) : verificationCodeTtl;
        }

        public String getMfaSecretEncryptionKey() {
            return mfaSecretEncryptionKey;
        }

        public void setMfaSecretEncryptionKey(String mfaSecretEncryptionKey) {
            this.mfaSecretEncryptionKey = mfaSecretEncryptionKey == null ? "" : mfaSecretEncryptionKey;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }

        public Duration getAccessTokenTtl() {
            return accessTokenTtl;
        }

        public void setAccessTokenTtl(Duration accessTokenTtl) {
            this.accessTokenTtl = accessTokenTtl;
        }

        public Duration getRefreshTokenTtl() {
            return refreshTokenTtl;
        }

        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = refreshTokenTtl;
        }
    }

    public static class CustodyWallet {
        private boolean enabled = false;
        private String baseUrl = "http://localhost:8002";
        private String apiKey = "";
        private String apiSecret = "";
        private String webhookSecret = "";
        private String spotAccountBaseUrl = "http://localhost:9086";
        private String spotAccountInternalSecret = "";
        private Map<String, Long> assetScales = Map.of();
        private Map<String, String> withdrawalAddressIds = Map.of();
        private Duration requestTimeout = Duration.ofSeconds(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiSecret() {
            return apiSecret;
        }

        public void setApiSecret(String apiSecret) {
            this.apiSecret = apiSecret;
        }

        public String getWebhookSecret() {
            return webhookSecret;
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }

        public String getSpotAccountBaseUrl() {
            return spotAccountBaseUrl;
        }

        public void setSpotAccountBaseUrl(String spotAccountBaseUrl) {
            this.spotAccountBaseUrl = spotAccountBaseUrl;
        }

        public String getSpotAccountInternalSecret() {
            return spotAccountInternalSecret;
        }

        public void setSpotAccountInternalSecret(String spotAccountInternalSecret) {
            this.spotAccountInternalSecret = spotAccountInternalSecret;
        }

        public Map<String, Long> getAssetScales() {
            return assetScales;
        }

        public void setAssetScales(Map<String, Long> assetScales) {
            this.assetScales = assetScales == null ? Map.of() : Map.copyOf(assetScales);
        }

        public void setAssetScalesJson(String assetScalesJson) {
            if (assetScalesJson != null && !assetScalesJson.trim().equals("{}")) {
                setAssetScales(readLongMap(assetScalesJson));
            }
        }

        public Map<String, String> getWithdrawalAddressIds() {
            return withdrawalAddressIds;
        }

        public void setWithdrawalAddressIds(Map<String, String> withdrawalAddressIds) {
            this.withdrawalAddressIds = withdrawalAddressIds == null ? Map.of() : Map.copyOf(withdrawalAddressIds);
        }

        public void setWithdrawalAddressIdsJson(String withdrawalAddressIdsJson) {
            if (withdrawalAddressIdsJson != null && !withdrawalAddressIdsJson.trim().equals("{}")) {
                setWithdrawalAddressIds(readStringMap(withdrawalAddressIdsJson));
            }
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout == null || requestTimeout.isNegative()
                    || requestTimeout.isZero() ? Duration.ofSeconds(10) : requestTimeout;
        }
    }

    public static class Withdrawal {
        private java.math.BigDecimal singleApprovalThresholdUsdt = new java.math.BigDecimal("10000");
        private java.math.BigDecimal dailyLimitUsdt = new java.math.BigDecimal("50000");
        private String valuationBaseUrl = "http://localhost:9082";
        private Duration valuationMaxAge = Duration.ofSeconds(30);
        private Duration failureReconciliationDelay = Duration.ofSeconds(30);

        public java.math.BigDecimal getSingleApprovalThresholdUsdt() {
            return singleApprovalThresholdUsdt;
        }

        public void setSingleApprovalThresholdUsdt(java.math.BigDecimal value) {
            this.singleApprovalThresholdUsdt = value;
        }

        public java.math.BigDecimal getDailyLimitUsdt() {
            return dailyLimitUsdt;
        }

        public void setDailyLimitUsdt(java.math.BigDecimal value) {
            this.dailyLimitUsdt = value;
        }

        public String getValuationBaseUrl() {
            return valuationBaseUrl;
        }

        public void setValuationBaseUrl(String value) {
            this.valuationBaseUrl = value == null ? "" : value.trim();
        }

        public Duration getValuationMaxAge() {
            return valuationMaxAge;
        }

        public void setValuationMaxAge(Duration value) {
            this.valuationMaxAge = value == null || value.isZero() || value.isNegative()
                    ? Duration.ofSeconds(30) : value;
        }

        public Duration getFailureReconciliationDelay() {
            return failureReconciliationDelay;
        }

        public void setFailureReconciliationDelay(Duration value) {
            this.failureReconciliationDelay = value == null || value.isZero() || value.isNegative()
                    ? Duration.ofSeconds(30) : value;
        }
    }

    public static class ProductTransfer {
        private Duration reconciliationDelay = Duration.ofSeconds(5);
        private int reconciliationBatchSize = 100;
        private java.math.BigDecimal verificationThresholdUsdt = new java.math.BigDecimal("10000");

        public Duration getReconciliationDelay() {
            return reconciliationDelay;
        }

        public void setReconciliationDelay(Duration value) {
            reconciliationDelay = value == null || value.isZero() || value.isNegative()
                    ? Duration.ofSeconds(5) : value;
        }

        public int getReconciliationBatchSize() {
            return reconciliationBatchSize;
        }

        public void setReconciliationBatchSize(int value) {
            reconciliationBatchSize = value <= 0 ? 100 : Math.min(value, 1000);
        }

        public java.math.BigDecimal getVerificationThresholdUsdt() {
            return verificationThresholdUsdt;
        }

        public void setVerificationThresholdUsdt(java.math.BigDecimal value) {
            verificationThresholdUsdt = value == null || value.signum() < 0
                    ? new java.math.BigDecimal("10000") : value;
        }
    }

    public static class BinanceApi {
        private boolean enabled = true;
        private Map<String, String> symbolAliases = Map.of();
        private Map<String, SymbolScale> symbolScales = Map.of();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, String> getSymbolAliases() {
            return symbolAliases;
        }

        public void setSymbolAliases(Map<String, String> symbolAliases) {
            this.symbolAliases = symbolAliases == null ? Map.of() : Map.copyOf(symbolAliases);
        }

        public void setSymbolAliasesJson(String symbolAliasesJson) {
            if (symbolAliasesJson != null && !symbolAliasesJson.trim().equals("{}")) {
                setSymbolAliases(readStringMap(symbolAliasesJson));
            }
        }

        public Map<String, SymbolScale> getSymbolScales() {
            return symbolScales;
        }

        public void setSymbolScales(Map<String, SymbolScale> symbolScales) {
            this.symbolScales = symbolScales == null ? Map.of() : Map.copyOf(symbolScales);
        }

        public void setSymbolScalesJson(String symbolScalesJson) {
            if (symbolScalesJson == null || symbolScalesJson.trim().equals("{}")) {
                return;
            }
            Map<String, SymbolScale> result = new LinkedHashMap<>();
            readJsonObject(symbolScalesJson).forEach((key, value) -> {
                if (!(value instanceof Map<?, ?> scale)) {
                    throw new IllegalArgumentException("symbol scale must be an object");
                }
                SymbolScale target = new SymbolScale();
                target.setPriceScale(integer(scale.get("priceScale"), "priceScale"));
                target.setQuantityScale(integer(scale.get("quantityScale"), "quantityScale"));
                result.put(key.toString(), target);
            });
            setSymbolScales(result);
        }

        private static int integer(Object value, String field) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("symbol scale " + field + " is invalid", ex);
            }
        }

        public String backendSymbol(String symbol) {
            String normalized = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
            return symbolAliases.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(normalized))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(normalized);
        }

        public SymbolScale scale(String symbol) {
            String normalized = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
            return symbolScales.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(normalized))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("symbol scale is not configured: " + symbol));
        }
    }

    public static class KycDocuments {
        private boolean enabled;
        private String type = "s3";
        private String endpoint = "";
        private String bucket = "";
        private String region = "us-east-1";
        private String accessKey = "";
        private String secretKey = "";
        private String rootPath = "/tmp/surprising-kyc-documents";
        private String prefix = "kyc";
        private long maxFileSizeBytes = 15L * 1024L * 1024L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type == null || type.isBlank() ? "s3" : type.trim().toLowerCase(java.util.Locale.ROOT);
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint == null ? "" : endpoint.trim().replaceAll("/$", "");
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket == null ? "" : bucket.trim();
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region == null || region.isBlank() ? "us-east-1" : region.trim();
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey == null ? "" : accessKey.trim();
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey == null ? "" : secretKey;
        }

        public String getRootPath() {
            return rootPath;
        }

        public void setRootPath(String rootPath) {
            this.rootPath = rootPath == null || rootPath.isBlank() ? "/tmp/surprising-kyc-documents" : rootPath;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix == null || prefix.isBlank() ? "kyc" : prefix.trim().replaceAll("^/+|/+$", "");
        }

        public long getMaxFileSizeBytes() {
            return maxFileSizeBytes;
        }

        public void setMaxFileSizeBytes(long maxFileSizeBytes) {
            this.maxFileSizeBytes = maxFileSizeBytes <= 0 ? 15L * 1024L * 1024L : maxFileSizeBytes;
        }
    }

    public static class SymbolScale {
        private int priceScale;
        private int quantityScale;

        public int getPriceScale() {
            return priceScale;
        }

        public void setPriceScale(int priceScale) {
            this.priceScale = priceScale;
        }

        public int getQuantityScale() {
            return quantityScale;
        }

        public void setQuantityScale(int quantityScale) {
            this.quantityScale = quantityScale;
        }
    }

    public static class HttpClient {
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(5);

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public static class Observability {
        private KafkaLag kafka = new KafkaLag();
        private WebSocketMonitor webSocket = new WebSocketMonitor();
        private PrometheusMonitor prometheus = new PrometheusMonitor();

        public KafkaLag getKafka() {
            return kafka;
        }

        public void setKafka(KafkaLag kafka) {
            this.kafka = kafka == null ? new KafkaLag() : kafka;
        }

        public WebSocketMonitor getWebSocket() {
            return webSocket;
        }

        public void setWebSocket(WebSocketMonitor webSocket) {
            this.webSocket = webSocket == null ? new WebSocketMonitor() : webSocket;
        }

        public PrometheusMonitor getPrometheus() {
            return prometheus;
        }

        public void setPrometheus(PrometheusMonitor prometheus) {
            this.prometheus = prometheus == null ? new PrometheusMonitor() : prometheus;
        }
    }

    public static class KafkaLag {
        private boolean enabled = false;
        private String bootstrapServers = "localhost:9092";
        private String clientId = "surprising-gateway-admin-monitor";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private Duration requestTimeout = Duration.ofSeconds(3);
        private int maxPartitionsPerGroup = 200;
        private List<KafkaConsumerGroup> consumerGroups = defaultKafkaConsumerGroups();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers == null || bootstrapServers.isBlank()
                    ? "localhost:9092"
                    : bootstrapServers;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId == null || clientId.isBlank()
                    ? "surprising-gateway-admin-monitor"
                    : clientId;
        }

        public ProductLine getProductLine() {
            return productLine;
        }

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        }

        public boolean isProductTopicsEnabled() {
            return productTopicsEnabled;
        }

        public void setProductTopicsEnabled(boolean productTopicsEnabled) {
            this.productTopicsEnabled = productTopicsEnabled;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                    ? Duration.ofSeconds(3)
                    : requestTimeout;
        }

        public int getMaxPartitionsPerGroup() {
            return maxPartitionsPerGroup;
        }

        public void setMaxPartitionsPerGroup(int maxPartitionsPerGroup) {
            this.maxPartitionsPerGroup = Math.max(1, maxPartitionsPerGroup);
        }

        public List<KafkaConsumerGroup> getConsumerGroups() {
            return productTopicsEnabled ? productKafkaConsumerGroups(productLine) : consumerGroups;
        }

        public void setConsumerGroups(List<KafkaConsumerGroup> consumerGroups) {
            this.consumerGroups = consumerGroups == null ? List.of() : List.copyOf(consumerGroups);
        }

        private static List<KafkaConsumerGroup> defaultKafkaConsumerGroups() {
            return List.of(
                    new KafkaConsumerGroup("surprising-matching-v1", List.of("surprising.perp.order.commands.v1")),
                    new KafkaConsumerGroup("surprising-linear-perp-account-user-command-v1",
                            List.of("surprising.linear-perp.account.user.commands.v1")),
                    new KafkaConsumerGroup("surprising-risk-v1", List.of("surprising.account.position.events.v1")),
                    new KafkaConsumerGroup("surprising-trigger-v1", List.of(
                            "surprising.perp.mark.price.v1",
                            "surprising.perp.index.price.v1",
                            "surprising.perp.match.trades.v1",
                            "surprising.account.position.events.v1")),
                    new KafkaConsumerGroup("surprising-mark-price-v1", List.of(
                            "surprising.perp.index.price.v1",
                            "surprising.perp.book.ticker.v1",
                            "surprising.perp.trade.events.v1",
                            "surprising.perp.funding.rate.v1")),
                    new KafkaConsumerGroup("surprising-insurance-v1", List.of(
                            "surprising.account.liquidation-fee.events.v1")));
        }

        private static List<KafkaConsumerGroup> productKafkaConsumerGroups(ProductLine productLine) {
            ProductTopicNames topics = ProductTopicNames.of(productLine);
            return List.of(
                    new KafkaConsumerGroup(topics.consumerGroup("matching"),
                            List.of(topics.orderCommandsTopic())),
                    new KafkaConsumerGroup(topics.consumerGroup("account-user-command"),
                            List.of(topics.accountUserCommandsTopic())),
                    new KafkaConsumerGroup(topics.consumerGroup("risk"),
                            List.of(topics.accountPositionEventsTopic())),
                    new KafkaConsumerGroup(topics.consumerGroup("trigger"),
                            List.of(topics.markPriceTopic(), topics.indexPriceTopic(), topics.matchTradesTopic(),
                                    topics.accountPositionEventsTopic())),
                    new KafkaConsumerGroup(topics.consumerGroup("mark-price"),
                            markPriceConsumerTopics(productLine, topics)),
                    new KafkaConsumerGroup(topics.consumerGroup("candlestick"),
                            List.of(topics.matchTradesTopic())));
        }

        private static List<String> markPriceConsumerTopics(ProductLine productLine, ProductTopicNames topics) {
            List<String> topicNames = new ArrayList<>(List.of(
                    topics.indexPriceTopic(),
                    topics.bookTickerTopic(),
                    topics.publicTradesTopic()));
            if (productLine.isFundingProduct()) {
                topicNames.add(topics.fundingRateTopic());
            }
            return List.copyOf(topicNames);
        }
    }

    public static class KafkaConsumerGroup {
        private String groupId;
        private List<String> topics = new ArrayList<>();

        public KafkaConsumerGroup() {
        }

        public KafkaConsumerGroup(String groupId, List<String> topics) {
            this.groupId = groupId;
            this.topics = topics == null ? List.of() : List.copyOf(topics);
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public List<String> getTopics() {
            return topics;
        }

        public void setTopics(List<String> topics) {
            this.topics = topics == null ? List.of() : List.copyOf(topics);
        }
    }

    public static class WebSocketMonitor {
        private boolean enabled = true;
        private String adminRoute = "websocket-admin";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAdminRoute() {
            return adminRoute;
        }

        public void setAdminRoute(String adminRoute) {
            this.adminRoute = adminRoute == null || adminRoute.isBlank() ? "websocket-admin" : adminRoute;
        }
    }

    public static class PrometheusMonitor {
        private boolean enabled = true;
        private int samplePreviewLimit = 8;
        private int maxBodyBytes = 1_000_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSamplePreviewLimit() {
            return samplePreviewLimit;
        }

        public void setSamplePreviewLimit(int samplePreviewLimit) {
            this.samplePreviewLimit = Math.max(0, samplePreviewLimit);
        }

        public int getMaxBodyBytes() {
            return maxBodyBytes;
        }

        public void setMaxBodyBytes(int maxBodyBytes) {
            this.maxBodyBytes = Math.max(1024, maxBodyBytes);
        }
    }

    public static class BackendRoute {
        private String baseUrl;
        private String targetPrefix;
        private boolean privateRoute;
        private String basicAuthUsername;
        private String basicAuthPassword;
        private Map<ProductLine, ProductRoute> productRoutes = new LinkedHashMap<>();

        public BackendRoute() {
        }

        public BackendRoute(String baseUrl, String targetPrefix, boolean privateRoute) {
            this.baseUrl = baseUrl;
            this.targetPrefix = targetPrefix;
            this.privateRoute = privateRoute;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getTargetPrefix() {
            return targetPrefix;
        }

        public void setTargetPrefix(String targetPrefix) {
            this.targetPrefix = targetPrefix;
        }

        public boolean isPrivateRoute() {
            return privateRoute;
        }

        public void setPrivateRoute(boolean privateRoute) {
            this.privateRoute = privateRoute;
        }

        public String getBasicAuthUsername() {
            return basicAuthUsername;
        }

        public void setBasicAuthUsername(String basicAuthUsername) {
            this.basicAuthUsername = basicAuthUsername;
        }

        public String getBasicAuthPassword() {
            return basicAuthPassword;
        }

        public void setBasicAuthPassword(String basicAuthPassword) {
            this.basicAuthPassword = basicAuthPassword;
        }

        public boolean hasBasicAuth() {
            return basicAuthUsername != null && !basicAuthUsername.isBlank()
                    && basicAuthPassword != null && !basicAuthPassword.isBlank();
        }

        public Map<ProductLine, ProductRoute> getProductRoutes() {
            return productRoutes;
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

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getTargetPrefix() {
            return targetPrefix;
        }

        public void setTargetPrefix(String targetPrefix) {
            this.targetPrefix = targetPrefix;
        }

        public String getBasicAuthUsername() {
            return basicAuthUsername;
        }

        public void setBasicAuthUsername(String basicAuthUsername) {
            this.basicAuthUsername = basicAuthUsername;
        }

        public String getBasicAuthPassword() {
            return basicAuthPassword;
        }

        public void setBasicAuthPassword(String basicAuthPassword) {
            this.basicAuthPassword = basicAuthPassword;
        }
    }
}
