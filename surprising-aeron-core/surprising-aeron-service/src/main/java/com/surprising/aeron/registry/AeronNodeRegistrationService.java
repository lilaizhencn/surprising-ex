package com.surprising.aeron.registry;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.surprising.aeron.config.ClusterTopology;
import com.surprising.aeron.controller.ClusterRoleChangeListener;
import com.surprising.aeron.protocol.ProductLineClusterLayout;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 负责将当前 Aeron Core 节点自动注册至 Nacos 注册中心，
 * 并实时上报产品线、节点编号、端口布局以及 Leader/Follower 角色。
 */
@Component
@EnableConfigurationProperties(AeronRegistryProperties.class)
public class AeronNodeRegistrationService implements SmartInitializingSingleton, DisposableBean, ClusterRoleChangeListener {

    private static final Logger log = LoggerFactory.getLogger(AeronNodeRegistrationService.class);

    private final ClusterTopology topology;
    private final AeronRegistryProperties properties;
    private final AtomicReference<Cluster.Role> currentRole = new AtomicReference<>(Cluster.Role.FOLLOWER);
    private final AtomicBoolean registered = new AtomicBoolean(false);

    private NamingService namingService;
    private ScheduledExecutorService retryExecutor;
    private volatile boolean closed = false;

    public AeronNodeRegistrationService(ClusterTopology topology, AeronRegistryProperties properties) {
        this.topology = topology;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.enabled()) {
            log.info("Aeron node Nacos registration is disabled productLine={} nodeId={}",
                    topology.productLine(), topology.nodeId());
            return;
        }
        initNamingService();
        registerWithRetry();
    }

    private synchronized void initNamingService() {
        if (namingService != null || closed) {
            return;
        }
        try {
            Properties nacosProps = new Properties();
            nacosProps.put(PropertyKeyConst.SERVER_ADDR, properties.serverAddr());
            this.namingService = NamingFactory.createNamingService(nacosProps);
            log.info("Initialized Nacos NamingService for Aeron node serverAddr={}", properties.serverAddr());
        } catch (Exception e) {
            log.warn("Failed to initialize Nacos NamingService (will retry on background): {}", e.getMessage());
        }
    }

    @Override
    public void onRoleChanged(Cluster.Role newRole) {
        Cluster.Role prev = currentRole.getAndSet(newRole);
        log.info("Aeron node role changed productLine={} nodeId={} prev={} new={}",
                topology.productLine(), topology.nodeId(), prev, newRole);
        if (properties.enabled() && !closed) {
            // 角色变更后更新 Nacos 实例元数据
            doRegister();
        }
    }

    private void registerWithRetry() {
        if (!doRegister()) {
            if (retryExecutor == null) {
                retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "aeron-nacos-register-retry");
                    t.setDaemon(true);
                    return t;
                });
            }
            retryExecutor.schedule(this::retryTask, 3, TimeUnit.SECONDS);
        }
    }

    private void retryTask() {
        if (closed || registered.get()) {
            return;
        }
        if (namingService == null) {
            initNamingService();
        }
        if (!doRegister() && !closed) {
            retryExecutor.schedule(this::retryTask, 5, TimeUnit.SECONDS);
        }
    }

    private synchronized boolean doRegister() {
        if (namingService == null || closed) {
            return false;
        }
        try {
            Instance instance = buildInstance();
            namingService.registerInstance(properties.serviceName(), properties.group(), instance);
            registered.set(true);
            log.info("Successfully registered Aeron node to Nacos serviceName={} ip={} port={} productLine={} nodeId={} role={}",
                    properties.serviceName(), instance.getIp(), instance.getPort(),
                    topology.productLine(), topology.nodeId(), currentRole.get());
            return true;
        } catch (Exception e) {
            log.warn("Failed to register Aeron node to Nacos (serviceName={}): {}", properties.serviceName(), e.getMessage());
            return false;
        }
    }

    private Instance buildInstance() {
        ProductLine productLine = topology.productLine();
        int nodeId = topology.nodeId();
        int clientPort = ProductLineClusterLayout.port(productLine, nodeId, ProductLineClusterLayout.CLIENT_FACING_OFFSET);
        String host = resolveHost();

        Instance instance = new Instance();
        instance.setIp(host);
        instance.setPort(clientPort);
        instance.setClusterName(properties.clusterName());
        instance.setHealthy(true);
        instance.setWeight(1.0);

        Map<String, String> metadata = new HashMap<>();
        metadata.put("productLine", productLine.name());
        metadata.put("clusterId", String.valueOf(ProductLineClusterLayout.clusterId(productLine)));
        metadata.put("nodeId", String.valueOf(nodeId));
        metadata.put("role", currentRole.get().name());
        metadata.put("ingressEndpoint", host + ":" + clientPort);
        metadata.put("archiveControlPort", String.valueOf(ProductLineClusterLayout.port(productLine, nodeId, ProductLineClusterLayout.ARCHIVE_CONTROL_OFFSET)));
        metadata.put("memberFacingPort", String.valueOf(ProductLineClusterLayout.port(productLine, nodeId, 3)));
        metadata.put("logPort", String.valueOf(ProductLineClusterLayout.port(productLine, nodeId, 4)));
        metadata.put("transferPort", String.valueOf(ProductLineClusterLayout.port(productLine, nodeId, 5)));

        instance.setMetadata(metadata);
        return instance;
    }

    private String resolveHost() {
        if (!properties.advertisedIp().isEmpty()) {
            return properties.advertisedIp();
        }
        String host = topology.hostname();
        if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) {
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (Exception ignored) {
                return "127.0.0.1";
            }
        }
        return host;
    }

    @Override
    public synchronized void destroy() {
        closed = true;
        if (retryExecutor != null) {
            retryExecutor.shutdownNow();
        }
        if (namingService != null && registered.get()) {
            try {
                ProductLine productLine = topology.productLine();
                int nodeId = topology.nodeId();
                int clientPort = ProductLineClusterLayout.port(productLine, nodeId, ProductLineClusterLayout.CLIENT_FACING_OFFSET);
                namingService.deregisterInstance(properties.serviceName(), properties.group(), resolveHost(), clientPort);
                namingService.shutDown();
                log.info("Deregistered Aeron node from Nacos productLine={} nodeId={}", productLine, nodeId);
            } catch (Exception e) {
                log.warn("Failed to deregister Aeron node from Nacos on shutdown: {}", e.getMessage());
            }
        }
    }
}
