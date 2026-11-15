package com.surprising.gateway.provider.config;

import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * HTTP client and backend route configuration models.
 */
public final class GatewayRoutingProperties {

    private GatewayRoutingProperties() {
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
        private Map<ProductLine, GatewayProperties.ProductRoute> productRoutes = new LinkedHashMap<>();

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

        public void setProductRoutes(Map<ProductLine, GatewayProperties.ProductRoute> productRoutes) {
            this.productRoutes = productRoutes == null ? Map.of() : Map.copyOf(productRoutes);
        }

        public boolean hasProductRoutes() {
            return productRoutes != null && !productRoutes.isEmpty();
        }

        public GatewayProperties.BackendRoute resolve(ProductLine productLine) {
            if (productLine == null || !hasProductRoutes()) {
                return (GatewayProperties.BackendRoute) this;
            }
            GatewayProperties.ProductRoute productRoute = productRoutes.get(productLine);
            if (productRoute == null) {
                return null;
            }
            GatewayProperties.BackendRoute resolved = new GatewayProperties.BackendRoute(
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
