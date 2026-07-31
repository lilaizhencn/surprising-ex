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

@ConfigurationProperties(prefix = "surprising.gateway")
public class GatewayProperties {

    private Security security = new Security();
    private HttpClient httpClient = new HttpClient();
    private Observability observability = new Observability();
    private Map<String, BackendRoute> routes = defaultRoutes();
    private Map<String, BackendRoute> adminRoutes = defaultAdminRoutes();

    /** 仅在启用 Kafka 监控时校验其产品线，避免监控配置影响网关启动。 */
    @PostConstruct
    void validateProductLineConfiguration() {
        if (observability.kafka.enabled) {
            ProductLineConfiguration.require(observability.kafka.productLine,
                    observability.kafka.productTopicsEnabled, "gateway-admin-monitor");
        }
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
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

    private static Map<String, BackendRoute> defaultRoutes() {
        Map<String, BackendRoute> routes = new LinkedHashMap<>();
        routes.put("instrument", new BackendRoute("http://localhost:9080", "/api/v1/instruments", false));
        routes.put("candlestick", new BackendRoute("http://localhost:9081", "/api/v1/candlestick", false));
        routes.put("price-index", new BackendRoute("http://localhost:9082", "/api/v1/price/index", false));
        routes.put("price-fx", new BackendRoute("http://localhost:9082", "/api/v1/price/fx", false));
        routes.put("price-mark", new BackendRoute("http://localhost:9083", "/api/v1/price/mark", false));
        routes.put("trading", new BackendRoute("http://localhost:9084", "/api/v1/trading/orders", true));
        routes.put("trading-market", new BackendRoute("http://localhost:9085", "/api/v1/trading/market", false));
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
        route.setBasicAuthUsername(System.getenv().getOrDefault("SW_WALLET_ADMIN_USERNAME", "admin"));
        route.setBasicAuthPassword(System.getenv().getOrDefault("SW_WALLET_ADMIN_PASSWORD", ""));
        return route;
    }

    public static class Security {
        private String userIdHeader = "X-User-Id";
        private boolean requireIdentityForPrivateRoutes = true;
        private boolean allowUserIdHeaderFallback = true;
        private List<String> adminRoles = List.of("SUPPORT", "ADMIN", "SUPER_ADMIN");
        private List<String> adminIpAllowlist = List.of();
        private boolean requireApprovalForHighRiskAdminWrites = true;
        private String adminApprovalHeader = "X-Admin-Approval-Id";
        private Duration adminApprovalTtl = Duration.ofMinutes(30);
        private boolean requireAdminMfa = false;
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
                    new KafkaConsumerGroup("surprising-liquidation-v1", List.of(
                            "surprising.perp.liquidation.candidates.v1",
                            "surprising.perp.match.results.v1")),
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
                    new KafkaConsumerGroup(topics.consumerGroup("liquidation"),
                            List.of(topics.liquidationCandidatesTopic(), topics.matchResultsTopic())),
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
