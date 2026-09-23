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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@ConfigurationProperties(prefix = "surprising.gateway")
public class GatewayProperties implements EnvironmentAware {

    private static final ObjectMapper JSON = new ObjectMapper();

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
        routes.put("instrument", new BackendRoute("local:", "/api/v1/instruments", false));
        routes.put("candlestick", new BackendRoute("http://localhost:9095", "/api/v1/candlestick", false));
        routes.put("price-index", new BackendRoute("http://localhost:9082", "/api/v1/price/index", false));
        routes.put("price-fx", new BackendRoute("http://localhost:9082", "/api/v1/price/fx", false));
        routes.put("price-mark", new BackendRoute("http://localhost:9082", "/api/v1/price/mark", false));
        routes.put("trading", new BackendRoute("local:", "/api/v1/trading/orders", true));
        routes.put("trading-leverage", new BackendRoute("local:", "/api/v1/trading/leverage", true));
        routes.put("trading-market", new BackendRoute("local:", "/api/v1/trading/market", false));
        routes.put("trading-trigger", new BackendRoute("local:", "/api/v1/trading/trigger-orders", true));
        routes.put("account", new BackendRoute("local:", "/api/v1/accounts", true));
        routes.put("risk", new BackendRoute("http://localhost:9087", "/api/v1/risk", true));
        routes.put("liquidation", new BackendRoute("http://localhost:9087", "/api/v1/liquidations", true));
        routes.put("funding", new BackendRoute("http://localhost:9087", "/api/v1/funding", false));
        routes.put("insurance", new BackendRoute("http://localhost:9087", "/api/v1/insurance", true));
        routes.put("adl", new BackendRoute("http://localhost:9087", "/api/v1/adl", true));
        routes.put("market-maker", new BackendRoute("http://localhost:9096", "/api/v1/market-maker", true));
        routes.put("wallet", new BackendRoute("http://localhost:8002", "/wallet/v1", true));
        return routes;
    }

    private static Map<String, BackendRoute> defaultAdminRoutes() {
        Map<String, BackendRoute> routes = new LinkedHashMap<>();
        routes.put("instrument", new BackendRoute("local:", "/api/v1/instruments", true));
        routes.put("instrument-admin", new BackendRoute("local:", "/api/v1/instruments/admin", true));
        routes.put("candlestick", new BackendRoute("http://localhost:9095", "/api/v1/candlestick", true));
        routes.put("price-index", new BackendRoute("http://localhost:9082", "/api/v1/price/index", true));
        routes.put("price-fx", new BackendRoute("http://localhost:9082", "/api/v1/price/fx", true));
        routes.put("price-mark", new BackendRoute("http://localhost:9082", "/api/v1/price/mark", true));
        routes.put("trading", new BackendRoute("local:", "/api/v1/admin/trading/orders", true));
        routes.put("trading-orders", new BackendRoute("local:", "/api/v1/admin/trading/orders", true));
        routes.put("trading-fees", new BackendRoute("local:", "/api/v1/admin/trading/fees", true));
        routes.put("trading-market", new BackendRoute("local:", "/api/v1/trading/market", true));
        routes.put("trading-trigger", new BackendRoute("local:", "/api/v1/admin/trading/trigger-orders", true));
        routes.put("account", new BackendRoute("local:", "/api/v1/admin/accounts", true));
        routes.put("account-public", new BackendRoute("local:", "/api/v1/accounts", true));
        routes.put("risk", new BackendRoute("http://localhost:9087", "/api/v1/risk", true));
        routes.put("risk-admin", new BackendRoute("http://localhost:9087", "/api/v1/admin/risk", true));
        routes.put("liquidation", new BackendRoute("http://localhost:9087", "/api/v1/liquidations", true));
        routes.put("liquidation-admin", new BackendRoute("http://localhost:9087", "/api/v1/admin/liquidations", true));
        routes.put("funding", new BackendRoute("http://localhost:9087", "/api/v1/funding", true));
        routes.put("insurance", new BackendRoute("http://localhost:9087", "/api/v1/insurance", true));
        routes.put("insurance-admin", new BackendRoute("http://localhost:9087", "/api/v1/insurance/admin", true));
        routes.put("adl", new BackendRoute("http://localhost:9087", "/api/v1/adl", true));
        routes.put("market-maker", new BackendRoute("http://localhost:9096", "/api/v1/admin/market-maker", true));
        routes.put("wallet", new BackendRoute("http://localhost:8002", "/wallet/v1", true));
        routes.put("wallet-admin", walletAdminRoute());
        routes.put("websocket-admin", new BackendRoute("local:", "/api/v1/admin/websocket", true));
        return routes;
    }

    private static BackendRoute walletAdminRoute() {
        BackendRoute route = new BackendRoute("http://localhost:8002", "/wallet/v1/admin", true);
        route.setBasicAuthUsername(System.getenv().getOrDefault("SW_WALLET_ADMIN_USERNAME", ""));
        route.setBasicAuthPassword(System.getenv().getOrDefault("SW_WALLET_ADMIN_PASSWORD", ""));
        return route;
    }

    @Getter
    public static class Security {
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
        private Duration verificationCodeTtl = Duration.ofMinutes(10);
        private String mfaSecretEncryptionKey = "";
        @Setter
        private String issuer = "surprising-ex-gateway";
        @Setter
        private String jwtSecret = "local-dev-change-me-surprising-ex-gateway-secret-2026";
        @Setter
        private Duration accessTokenTtl = Duration.ofMinutes(30);
        @Setter
        private Duration refreshTokenTtl = Duration.ofDays(30);

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
            this.resendBaseUrl = resendBaseUrl == null || resendBaseUrl.isBlank()
                    ? "https://api.resend.com" : resendBaseUrl.replaceAll("/$", "");
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

    @Getter
    public static class CustodyWallet {
        @Setter
        private boolean enabled = false;
        @Setter
        private String baseUrl = "http://localhost:8002";
        @Setter
        private String apiKey = "";
        @Setter
        private String apiSecret = "";
        @Setter
        private String webhookSecret = "";
        @Setter
        private String spotAccountBaseUrl = "";

        private Map<String, Long> assetScales = Map.of();
        private Map<String, String> withdrawalAddressIds = Map.of();
        private Duration requestTimeout = Duration.ofSeconds(10);



        public void setAssetScales(Map<String, Long> assetScales) {
            this.assetScales = assetScales == null ? Map.of() : Map.copyOf(assetScales);
        }

        public void setAssetScalesJson(String assetScalesJson) {
            if (assetScalesJson != null && !assetScalesJson.trim().equals("{}")) {
                setAssetScales(readLongMap(assetScalesJson));
            }
        }

        public void setWithdrawalAddressIds(Map<String, String> withdrawalAddressIds) {
            this.withdrawalAddressIds = withdrawalAddressIds == null ? Map.of() : Map.copyOf(withdrawalAddressIds);
        }

        public void setWithdrawalAddressIdsJson(String withdrawalAddressIdsJson) {
            if (withdrawalAddressIdsJson != null && !withdrawalAddressIdsJson.trim().equals("{}")) {
                setWithdrawalAddressIds(readStringMap(withdrawalAddressIdsJson));
            }
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout == null || requestTimeout.isNegative()
                    || requestTimeout.isZero() ? Duration.ofSeconds(10) : requestTimeout;
        }
    }

    @Getter
    public static class Withdrawal {
        @Setter
        private java.math.BigDecimal singleApprovalThresholdUsdt = new java.math.BigDecimal("10000");
        @Setter
        private java.math.BigDecimal dailyLimitUsdt = new java.math.BigDecimal("50000");
        private String valuationBaseUrl = "http://localhost:9082";
        private Duration valuationMaxAge = Duration.ofSeconds(30);
        private Duration failureReconciliationDelay = Duration.ofSeconds(30);

        public void setValuationBaseUrl(String value) {
            this.valuationBaseUrl = value == null ? "" : value.trim();
        }

        public void setValuationMaxAge(Duration value) {
            this.valuationMaxAge = value == null || value.isZero() || value.isNegative()
                    ? Duration.ofSeconds(30) : value;
        }

        public void setFailureReconciliationDelay(Duration value) {
            this.failureReconciliationDelay = value == null || value.isZero() || value.isNegative()
                    ? Duration.ofSeconds(30) : value;
        }
    }

    @Getter
    public static class ProductTransfer {
        private boolean enabled = true;
        private Duration reconciliationDelay = Duration.ofSeconds(5);
        private int reconciliationBatchSize = 100;
        private java.math.BigDecimal verificationThresholdUsdt = new java.math.BigDecimal("10000");

        public void setEnabled(boolean value) {
            enabled = value;
        }

        public void setReconciliationDelay(Duration value) {
            reconciliationDelay = value == null || value.isZero() || value.isNegative()
                    ? Duration.ofSeconds(5) : value;
        }

        public void setReconciliationBatchSize(int value) {
            reconciliationBatchSize = value <= 0 ? 100 : Math.min(value, 1000);
        }

        public void setVerificationThresholdUsdt(java.math.BigDecimal value) {
            verificationThresholdUsdt = value == null || value.signum() < 0
                    ? new java.math.BigDecimal("10000") : value;
        }
    }

    @Getter
    public static class BinanceApi {
        @Setter
        private boolean enabled = true;
        private Map<String, String> symbolAliases = Map.of();
        private Map<String, SymbolScale> symbolScales = Map.of();

        public void setSymbolAliases(Map<String, String> symbolAliases) {
            this.symbolAliases = symbolAliases == null ? Map.of() : Map.copyOf(symbolAliases);
        }

        public void setSymbolAliasesJson(String symbolAliasesJson) {
            if (symbolAliasesJson != null && !symbolAliasesJson.trim().equals("{}")) {
                setSymbolAliases(readStringMap(symbolAliasesJson));
            }
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

    @Getter
    public static class KycDocuments {
        @Setter
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

        public void setType(String type) {
            this.type = type == null || type.isBlank() ? "s3" : type.trim().toLowerCase(java.util.Locale.ROOT);
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint == null ? "" : endpoint.trim().replaceAll("/$", "");
        }

        public void setBucket(String bucket) {
            this.bucket = bucket == null ? "" : bucket.trim();
        }

        public void setRegion(String region) {
            this.region = region == null || region.isBlank() ? "us-east-1" : region.trim();
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey == null ? "" : accessKey.trim();
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey == null ? "" : secretKey;
        }

        public void setRootPath(String rootPath) {
            this.rootPath = rootPath == null || rootPath.isBlank() ? "/tmp/surprising-kyc-documents" : rootPath;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix == null || prefix.isBlank() ? "kyc" : prefix.trim().replaceAll("^/+|/+$", "");
        }

        public void setMaxFileSizeBytes(long maxFileSizeBytes) {
            this.maxFileSizeBytes = maxFileSizeBytes <= 0 ? 15L * 1024L * 1024L : maxFileSizeBytes;
        }
    }

    @Getter
    @Setter
    public static class SymbolScale {
        private int priceScale;
        private int quantityScale;

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
