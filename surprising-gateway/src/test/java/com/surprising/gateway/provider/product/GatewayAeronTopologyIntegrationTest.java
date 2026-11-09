package com.surprising.gateway.provider.product;

import com.surprising.aeron.client.topology.AeronClusterTopologyProvider;
import com.surprising.gateway.provider.config.GatewayProductsProperties;
import com.surprising.gateway.provider.service.GatewayProductSettings;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayAeronTopologyIntegrationTest {

    @Test
    @DisplayName("验证 Gateway 能自动通过 AeronClusterTopologyProvider 获取 3 节点动态集群拓扑")
    void shouldResolveThreeNodeTopologyFromProvider() {
        GatewayProductsProperties configuration = new GatewayProductsProperties();
        GatewayProductsProperties.Core spotCore = new GatewayProductsProperties.Core();
        spotCore.setHostnames(List.of("127.0.0.1")); // 静态默认兜底
        spotCore.setEgressHostname("127.0.0.1");
        spotCore.setResponseTimeout(Duration.ofSeconds(5));
        spotCore.setClientConnections(1);
        configuration.getCores().put(ProductLine.SPOT, spotCore);

        GatewayProductSettings settings = mock(GatewayProductSettings.class);
        ConfigurableApplicationContext parent = mock(ConfigurableApplicationContext.class);

        // 模拟通过 Nacos 动态发现到了 3 节点
        AeronClusterTopologyProvider dynamicProvider = mock(AeronClusterTopologyProvider.class);
        when(dynamicProvider.lookupHostnames(ProductLine.SPOT))
                .thenReturn(List.of("10.0.0.1", "10.0.0.2", "10.0.0.3"));

        GatewayProductServices services = new GatewayProductServices(parent, configuration, settings, dynamicProvider);

        Map<String, Object> properties = services.productProperties(ProductLine.SPOT);

        assertThat(properties.get("surprising.account.aeron.hostnames"))
                .isEqualTo("10.0.0.1,10.0.0.2,10.0.0.3");
        assertThat(properties.get("surprising.trading.order.aeron.hostnames"))
                .isEqualTo("10.0.0.1,10.0.0.2,10.0.0.3");
        assertThat(properties.get("surprising.trading.trigger.aeron.hostnames"))
                .isEqualTo("10.0.0.1,10.0.0.2,10.0.0.3");
    }
}
