package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class GatewayProductRoutesConfigurationTest {

    @Test
    void applicationYmlProvidesProductRoutesForTradingServices() throws IOException {
        GatewayProperties properties = bindApplicationProperties(Map.of());

        assertProductRouteMatrix(properties.getRoutes().get("trading"));
        assertProductRouteMatrix(properties.getRoutes().get("trading-market"));
        assertProductRouteMatrix(properties.getRoutes().get("trading-trigger"));
        assertProductRouteMatrix(properties.getRoutes().get("account"));
        assertProductRouteMatrix(properties.getRoutes().get("risk"));
        assertProductRouteMatrix(properties.getRoutes().get("price-mark"));
        assertProductRouteMatrix(properties.getRoutes().get("candlestick"));
        assertProductRouteMatrix(properties.getAdminRoutes().get("trading-fees"));
        assertProductRouteMatrix(properties.getAdminRoutes().get("account"));
        assertProductRouteMatrix(properties.getAdminRoutes().get("market-maker"));

        GatewayProperties.BackendRoute trading = properties.getRoutes().get("trading");
        GatewayProperties.BackendRoute optionRoute = trading.resolve(ProductLine.OPTION);
        assertThat(optionRoute.getBaseUrl()).isEqualTo("http://localhost:9084");
        assertThat(optionRoute.getTargetPrefix()).isEqualTo("/api/v1/trading/orders");
    }

    @Test
    void productRouteBaseUrlCanBeOverriddenByEnvironment() throws IOException {
        GatewayProperties properties = bindApplicationProperties(Map.of(
                "GATEWAY_ROUTE_TRADING_OPTION_BASE_URL", "http://order-option:9284",
                "GATEWAY_ROUTE_ACCOUNT_LINEAR_DELIVERY_BASE_URL", "http://account-linear-delivery:9286"));

        GatewayProperties.BackendRoute optionTrading = properties.getRoutes().get("trading")
                .resolve(ProductLine.OPTION);
        assertThat(optionTrading.getBaseUrl()).isEqualTo("http://order-option:9284");
        assertThat(optionTrading.getTargetPrefix()).isEqualTo("/api/v1/trading/orders");

        GatewayProperties.BackendRoute deliveryAccount = properties.getAdminRoutes().get("account")
                .resolve(ProductLine.LINEAR_DELIVERY);
        assertThat(deliveryAccount.getBaseUrl()).isEqualTo("http://account-linear-delivery:9286");
        assertThat(deliveryAccount.getTargetPrefix()).isEqualTo("/api/v1/admin/accounts");
    }

    private static GatewayProperties bindApplicationProperties(Map<String, Object> overrides) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-overrides", overrides));
        var loader = new YamlPropertySourceLoader();
        for (var source : loader.load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment)
                .bind("surprising.gateway", Bindable.of(GatewayProperties.class))
                .orElseThrow(() -> new IllegalStateException("surprising.gateway properties not bound"));
    }

    private static void assertProductRouteMatrix(GatewayProperties.BackendRoute route) {
        assertThat(route).isNotNull();
        assertThat(route.getProductRoutes()).containsOnlyKeys(ProductLine.values());
    }
}
