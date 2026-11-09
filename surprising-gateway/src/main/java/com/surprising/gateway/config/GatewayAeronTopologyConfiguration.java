package com.surprising.gateway.config;

import com.surprising.aeron.client.topology.AeronClusterTopologyProvider;
import com.surprising.aeron.client.topology.NacosAeronClusterTopologyProvider;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gateway 自动装配 Aeron 集群动态服务发现组件。
 * 优先从 Nacos 发现当前集群 3 节点拓扑，无 Nacos 时自动回退至静态配置。
 */
@Configuration(proxyBeanMethods = false)
public class GatewayAeronTopologyConfiguration {

    @Bean
    @ConditionalOnProperty(name = "surprising.aeron.topology.nacos.enabled", havingValue = "true", matchIfMissing = true)
    public AeronClusterTopologyProvider aeronClusterTopologyProvider(
            @Value("${spring.cloud.nacos.discovery.server-addr:${surprising.nacos.server-addr:127.0.0.1:8848}}") String nacosAddr) {
        return new NacosAeronClusterTopologyProvider(nacosAddr, AeronClusterTopologyProvider.ofStatic(List.of("127.0.0.1")));
    }
}
