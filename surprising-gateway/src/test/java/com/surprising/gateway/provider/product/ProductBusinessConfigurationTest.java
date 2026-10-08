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
                    .getKafka().getProductLine()).isNull();
            for(var line:ProductLine.values()) try(var child=new AnnotationConfigApplicationContext()) {
                child.setParent(parent);child.getEnvironment().merge(parent.getEnvironment());
                child.getEnvironment().getPropertySources().addFirst(new MapPropertySource("product",Map.of(
                        "surprising.account.kafka.product-line",line.name(),
                        "surprising.trading.order.kafka.product-line",line.name(),
                        "surprising.websocket.kafka.product-line",line.name())));
                ConfigurationPropertySources.attach(child.getEnvironment());
                child.registerBean("surprising.websocket-com.surprising.websocket.provider.config.WebSocketProperties", com.surprising.websocket.provider.config.WebSocketProperties.class,
                        () -> new com.surprising.gateway.product.ProductBusinessConfiguration().webSocketProperties(child.getEnvironment()));
                child.register(Properties.class,OrderInfrastructureConfiguration.class);child.refresh();
                assertThat(child.getBean(com.surprising.websocket.provider.config.WebSocketProperties.class).getKafka().getProductLine()).isEqualTo(line);
                assertThat(child.getBean(AccountProperties.class).getKafka().getProductLine()).isEqualTo(line);
                assertThat(child.getBean(TradingOrderProperties.class).getKafka().getProductLine()).isEqualTo(line);
            }
        }
    }
}
