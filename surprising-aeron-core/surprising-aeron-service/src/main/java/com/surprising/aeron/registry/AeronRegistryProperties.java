package com.surprising.aeron.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Aeron 节点向 Nacos 自动注册的配置属性。
 */
@ConfigurationProperties(prefix = "surprising.aeron.registry")
public record AeronRegistryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("127.0.0.1:8848") String serverAddr,
        @DefaultValue("surprising-aeron-core") String serviceName,
        @DefaultValue("DEFAULT_GROUP") String group,
        @DefaultValue("DEFAULT") String clusterName,
        @DefaultValue("") String advertisedIp) {

    public AeronRegistryProperties {
        serverAddr = (serverAddr == null || serverAddr.isBlank()) ? "127.0.0.1:8848" : serverAddr.trim();
        serviceName = (serviceName == null || serviceName.isBlank()) ? "surprising-aeron-core" : serviceName.trim();
        group = (group == null || group.isBlank()) ? "DEFAULT_GROUP" : group.trim();
        clusterName = (clusterName == null || clusterName.isBlank()) ? "DEFAULT" : clusterName.trim();
        advertisedIp = advertisedIp == null ? "" : advertisedIp.trim();
    }
}
