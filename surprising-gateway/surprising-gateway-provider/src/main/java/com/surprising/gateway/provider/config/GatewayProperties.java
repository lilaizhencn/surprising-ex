package com.surprising.gateway.provider.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.gateway")
public class GatewayProperties {

    private Security security = new Security();
    private HttpClient httpClient = new HttpClient();
    private Map<String, BackendRoute> routes = defaultRoutes();

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

    public Map<String, BackendRoute> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, BackendRoute> routes) {
        this.routes = routes;
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
        routes.put("trading-trigger", new BackendRoute("http://localhost:9095", "/api/v1/trading/trigger-orders", true));
        routes.put("account", new BackendRoute("http://localhost:9086", "/api/v1/accounts", true));
        routes.put("risk", new BackendRoute("http://localhost:9087", "/api/v1/risk", true));
        routes.put("liquidation", new BackendRoute("http://localhost:9088", "/api/v1/liquidations", true));
        routes.put("funding", new BackendRoute("http://localhost:9089", "/api/v1/funding", false));
        routes.put("insurance", new BackendRoute("http://localhost:9090", "/api/v1/insurance", true));
        routes.put("adl", new BackendRoute("http://localhost:9091", "/api/v1/adl", true));
        routes.put("market-maker", new BackendRoute("http://localhost:9096", "/api/v1/market-maker", true));
        return routes;
    }

    public static class Security {
        private String userIdHeader = "X-User-Id";
        private boolean requireIdentityForPrivateRoutes = true;
        private boolean allowUserIdHeaderFallback = true;
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

    public static class BackendRoute {
        private String baseUrl;
        private String targetPrefix;
        private boolean privateRoute;

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
    }
}
