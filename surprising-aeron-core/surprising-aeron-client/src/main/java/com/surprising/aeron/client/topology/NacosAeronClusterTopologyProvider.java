package com.surprising.aeron.client.topology;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.surprising.aeron.protocol.ProductLineClusterLayout;
import com.surprising.product.api.ProductLine;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Nacos 服务发现的动态 Aeron 集群拓扑提供者。
 * 自动从 Nacos 发现指定产品线的集群成员实例，提取主机地址并按 nodeId 严格排序；
 * 在未发现或异常时自动回退到 fallback 静态配置。
 */
public class NacosAeronClusterTopologyProvider implements AeronClusterTopologyProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NacosAeronClusterTopologyProvider.class);

    private final NamingService namingService;
    private final boolean ownsNamingService;
    private final String serviceName;
    private final String group;
    private final AeronClusterTopologyProvider fallback;
    private final Map<ProductLine, List<String>> cache = new ConcurrentHashMap<>();

    public NacosAeronClusterTopologyProvider(String serverAddr, AeronClusterTopologyProvider fallback) {
        this(serverAddr, "surprising-aeron-core", "DEFAULT_GROUP", fallback);
    }

    public NacosAeronClusterTopologyProvider(String serverAddr, String serviceName, String group, AeronClusterTopologyProvider fallback) {
        this(createNamingService(serverAddr), true, serviceName, group, fallback);
    }

    public NacosAeronClusterTopologyProvider(NamingService namingService, AeronClusterTopologyProvider fallback) {
        this(namingService, false, "surprising-aeron-core", "DEFAULT_GROUP", fallback);
    }

    public NacosAeronClusterTopologyProvider(
            NamingService namingService,
            boolean ownsNamingService,
            String serviceName,
            String group,
            AeronClusterTopologyProvider fallback) {
        this.namingService = Objects.requireNonNull(namingService, "namingService");
        this.ownsNamingService = ownsNamingService;
        this.serviceName = (serviceName == null || serviceName.isBlank()) ? "surprising-aeron-core" : serviceName.trim();
        this.group = (group == null || group.isBlank()) ? "DEFAULT_GROUP" : group.trim();
        this.fallback = fallback != null ? fallback : AeronClusterTopologyProvider.ofStatic(List.of("127.0.0.1"));
    }

    private static NamingService createNamingService(String serverAddr) {
        try {
            Properties properties = new Properties();
            properties.put(PropertyKeyConst.SERVER_ADDR, (serverAddr == null || serverAddr.isBlank()) ? "127.0.0.1:8848" : serverAddr.trim());
            return NamingFactory.createNamingService(properties);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create Nacos NamingService for Aeron topology: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> lookupHostnames(ProductLine productLine) {
        Objects.requireNonNull(productLine, "productLine");
        try {
            List<Instance> instances = namingService.selectInstances(serviceName, group, true);
            if (instances != null && !instances.isEmpty()) {
                List<Instance> matched = instances.stream()
                        .filter(inst -> {
                            Map<String, String> meta = inst.getMetadata();
                            return meta != null && productLine.name().equalsIgnoreCase(meta.get("productLine"));
                        })
                        .sorted(Comparator.comparingInt(this::extractNodeId))
                        .toList();

                if (matched.size() == 1 || matched.size() == ProductLineClusterLayout.MEMBER_COUNT) {
                    List<String> hostnames = matched.stream().map(Instance::getIp).toList();
                    cache.put(productLine, hostnames);
                    log.debug("Resolved Aeron cluster hostnames from Nacos productLine={} hostnames={}", productLine, hostnames);
                    return hostnames;
                } else if (!matched.isEmpty()) {
                    log.warn("Nacos returned unexpected instance count ({}) for productLine={}, required 1 or 3",
                            matched.size(), productLine);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query Aeron cluster topology from Nacos (serviceName={}): {}", serviceName, e.getMessage());
        }

        // 尝试从本地缓存返回
        List<String> cached = cache.get(productLine);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        // 回退兜底配置
        return fallback.lookupHostnames(productLine);
    }

    private int extractNodeId(Instance instance) {
        if (instance.getMetadata() != null) {
            String nodeIdStr = instance.getMetadata().get("nodeId");
            if (nodeIdStr != null) {
                try {
                    return Integer.parseInt(nodeIdStr.trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    @Override
    public void close() {
        if (ownsNamingService && namingService != null) {
            try {
                namingService.shutDown();
            } catch (Exception ignored) {
            }
        }
    }
}
