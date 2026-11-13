package com.surprising.gateway.provider.product;

import static org.assertj.core.api.Assertions.*;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.trading.order.config.OrderInfrastructureConfiguration;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class ProductBusinessConfigurationTest {
    @EnableConfigurationProperties(AccountProperties.class)
    static class Properties { }
    @EnableConfigurationProperties(com.surprising.websocket.provider.config.WebSocketProperties.class)
    static class RootProperties { }
    @Test void eachChildBindsItsOwnProductInsteadOfInheritingParentPropertyAdapter() {
        try (var parent=new AnnotationConfigApplicationContext()) {
            parent.getEnvironment().getPropertySources().addFirst(new MapPropertySource("parent",Map.of(
                    "surprising.account.kafka.product-line","LINEAR_PERPETUAL",
                    "surprising.trading.order.kafka.product-line","LINEAR_PERPETUAL",
                    "surprising.websocket.kafka.group-id","shared-node")));
            ConfigurationPropertySources.attach(parent.getEnvironment());
            parent.register(RootProperties.class);parent.refresh();
            assertThat(parent.getBean(com.surprising.websocket.provider.config.WebSocketProperties.class)
                    .getProductLine()).isNull();
            for(var line:ProductLine.values()) try(var child=new AnnotationConfigApplicationContext()) {
                child.setParent(parent);child.getEnvironment().merge(parent.getEnvironment());
                child.getEnvironment().getPropertySources().addFirst(new MapPropertySource("product",Map.of(
                        "surprising.account.kafka.product-line",line.name(),
                        "surprising.trading.order.kafka.product-line",line.name(),
                        "surprising.websocket.kafka.product-line",line.name())));
                ConfigurationPropertySources.attach(child.getEnvironment());
                child.registerBean("surprising.websocket-com.surprising.websocket.provider.config.WebSocketProperties", com.surprising.websocket.provider.config.WebSocketProperties.class,
                        () -> new com.surprising.gateway.config.ProductBusinessConfiguration().webSocketProperties(child.getEnvironment()));
                child.register(Properties.class,OrderInfrastructureConfiguration.class);child.refresh();
                assertThat(child.getBean(com.surprising.websocket.provider.config.WebSocketProperties.class).getProductLine()).isEqualTo(line);
                assertThat(child.getBean(AccountProperties.class).getProductLine()).isEqualTo(line);
                assertThat(child.getBean(TradingOrderProperties.class).getProductLine()).isEqualTo(line);
            }
        }
    }

    @Test void eachChildBindsPriceCandlestickAndLifecyclePropertiesPerProduct() {
        var gatewayConfig = new com.surprising.gateway.provider.config.GatewayProductsProperties();
        for (var line : ProductLine.values()) {
            gatewayConfig.getCores().put(line, new com.surprising.gateway.provider.config.GatewayProductsProperties.Core());
        }
        try (var parent = new AnnotationConfigApplicationContext()) {
            parent.getEnvironment().getPropertySources().addFirst(new MapPropertySource("parent", Map.of(
                    "surprising.candlestick.stream.state-dir", "/tmp/candles",
                    "surprising.price.mark.market-channel", "aeron:udp?endpoint=127.0.0.1:21407")));
            ConfigurationPropertySources.attach(parent.getEnvironment());
            parent.refresh();
            var services = new GatewayProductServices(
                    parent,
                    gatewayConfig,
                    org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayProductSettings.class));
            java.util.Set<String> stateDirs = new java.util.HashSet<>();
            java.util.Set<String> marketChannels = new java.util.HashSet<>();
            for (var line : ProductLine.values()) {
                try (var child = new AnnotationConfigApplicationContext()) {
                    child.setParent(parent);
                    child.getEnvironment().merge(parent.getEnvironment());
                    child.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("product-" + line, services.productProperties(line)));
                    ConfigurationPropertySources.attach(child.getEnvironment());
                    child.register(
                            LifecyclePropertiesConfig.class,
                            com.surprising.price.settings.config.PriceInfrastructureConfiguration.class,
                            com.surprising.derivatives.lifecycle.config.LifecycleInfrastructureConfiguration.class);
                    child.refresh();
                    assertThat(child.getBean(com.surprising.price.index.config.IndexPriceProperties.class).getProductLine())
                            .isEqualTo(line);
                    assertThat(child.getBean(com.surprising.price.mark.config.MarkPriceProperties.class).getProductLine())
                            .isEqualTo(line);
                    var candles = child.getBean(com.surprising.candlestick.provider.config.CandlestickProperties.class);
                    assertThat(candles.getProductLine()).isEqualTo(line);
                    assertThat(stateDirs.add(candles.getStream().getStateDir())).isTrue();
                    assertThat(marketChannels.add(child.getEnvironment().getProperty("surprising.price.mark.market-channel")))
                            .isTrue();
                    var risk = child.getBean(com.surprising.risk.provider.config.RiskProperties.class);
                    assertThat(risk.getProductLine()).isEqualTo(line);
                    assertThat(child.getBean(com.surprising.liquidation.provider.config.LiquidationProperties.class).getProductLine())
                            .isEqualTo(line);
                    assertThat(child.getBean(com.surprising.insurance.provider.config.InsuranceProperties.class).getProductLine())
                            .isEqualTo(line);
                    assertThat(child.getBean(com.surprising.adl.provider.config.AdlProperties.class).getProductLine())
                            .isEqualTo(line);
                    var funding = new com.surprising.derivatives.lifecycle.config.FundingConfiguration()
                            .fundingProperties(child.getEnvironment(), risk);
                    assertThat(funding.getProductLine()).isEqualTo(line);
                }
            }
        }
        assertThat(ProductInternalHandlerMapping.isProductEndpoint("/api/v1/price/mark/latest")).isTrue();
        assertThat(ProductInternalHandlerMapping.isProductEndpoint("/api/v1/price/index/latest")).isTrue();
        assertThat(ProductInternalHandlerMapping.isProductEndpoint("/api/v1/price/fx/latest")).isFalse();
        assertThat(ProductInternalHandlerMapping.isProductEndpoint("/api/v1/candlestick/candles")).isTrue();
        assertThat(ProductInternalHandlerMapping.isProductEndpoint("/api/v1/risk/rules")).isTrue();
        assertThat(ProductInternalHandlerMapping.isProductEndpoint("/api/v1/admin/risk/rules")).isTrue();
        assertThat(ProductInternalHandlerMapping.isProductEndpoint("/api/v1/liquidations/orders")).isTrue();
        assertThat(ProductInternalHandlerMapping.isProductEndpoint("/api/v1/funding/rates/latest")).isTrue();
        assertThat(ProductInternalHandlerMapping.isProductEndpoint("/api/v1/insurance/balances")).isTrue();
        assertThat(ProductInternalHandlerMapping.isProductEndpoint("/api/v1/adl/queue")).isTrue();
    }

    @EnableConfigurationProperties({
            com.surprising.candlestick.provider.config.CandlestickProperties.class,
            com.surprising.risk.provider.config.RiskProperties.class})
    static class LifecyclePropertiesConfig { }
}
