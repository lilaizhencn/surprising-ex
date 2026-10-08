package com.surprising.gateway.provider.product;

import com.surprising.gateway.provider.local.*;
import com.surprising.product.api.ProductLine;
import jakarta.annotation.PreDestroy;
import java.util.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 每条产品线拥有自己的连接、缓存、消费者和后台任务；共享父容器的认证、数据库及 WebSocket。 */
@Component
@DependsOn("instrumentService")
public final class GatewayProductServices implements AutoCloseable {
    private record Runtime(AnnotationConfigApplicationContext context, RequestMappingHandlerMapping mapping) { }
    private volatile Map<ProductLine, Runtime> products = Map.of();
    private volatile Map<ProductLine, String> errors = Map.of();
    private final GatewayProductsProperties configuration;
    private final GatewayProductSettings settings;
    private final ConfigurableApplicationContext parent;
    private boolean closed;

    public GatewayProductServices(ConfigurableApplicationContext parent, GatewayProductsProperties configuration,
                                  GatewayProductSettings settings) {
        this.parent = parent;
        this.configuration = configuration;
        this.settings = settings;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000, scheduler = "gatewayProductActivationScheduler")
    public synchronized void reload() {
        if (closed) return;
        var failures = new EnumMap<ProductLine, String>(ProductLine.class);
        for (var setting : settings.list()) {
            if (!setting.enabled() || products.containsKey(setting.productLine())) continue;
            var product = setting.productLine();
            try {
                var runtime = start(product);
                var updated = new EnumMap<ProductLine, Runtime>(ProductLine.class);
                updated.putAll(products);
                updated.put(product, runtime);
                products = Collections.unmodifiableMap(updated); // 完整初始化后一次发布，不暴露半成品。
            } catch (RuntimeException failure) {
                failures.put(product, "初始化失败，请检查 Core、Kafka 连接及本机服务日志；系统会自动重试");
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("product activation failed product={}", product, failure);
            }
        }
        errors = Collections.unmodifiableMap(failures);
    }

    private Runtime start(ProductLine product) {
        configuration.validate(product);
        var child = new AnnotationConfigApplicationContext();
        try {
            child.setId(parent.getId() + "/" + product.name());
            child.setParent(parent);
            child.getEnvironment().merge(parent.getEnvironment());
            child.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "gateway-product-" + product, productProperties(product)));
            org.springframework.boot.context.properties.source.ConfigurationPropertySources.attach(child.getEnvironment());
            child.register(com.surprising.gateway.product.ProductBusinessConfiguration.class, LocalBusinessApi.class,
                    TradingLocalRoutes.class, AccountLocalRoutes.class, InstrumentLocalRoutes.class);
            child.refresh();
            var mapping = new RequestMappingHandlerMapping();
            mapping.setApplicationContext(child);
            mapping.afterPropertiesSet();
            // 必须实际连通 Core；失败的上下文不参与请求路由或 WebSocket 订阅。
            var response = child.getBean(com.surprising.account.provider.service.AccountAeronGateway.class)
                    .query(com.surprising.aeron.protocol.CoreMessageType.LANE_METRICS_QUERY, UUID.randomUUID(), new byte[0]);
            if (response.status() != com.surprising.aeron.protocol.ResponseStatus.OK)
                throw new IllegalStateException("Core readiness rejected: " + response.resultCode());
            awaitConsumerAssignments(child);
            return new Runtime(child, mapping);
        } catch (RuntimeException | Error failure) {
            try { child.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void awaitConsumerAssignments(AnnotationConfigApplicationContext child) {
        var listeners = child.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class)
                .getListenerContainers().stream().filter(org.springframework.kafka.listener.MessageListenerContainer::isAutoStartup).toList();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (listeners.stream().anyMatch(listener -> !listener.isRunning()
                || listener.getAssignedPartitions() == null || listener.getAssignedPartitions().isEmpty())) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Kafka consumers are not assigned");
            try { java.util.concurrent.TimeUnit.MILLISECONDS.sleep(25); }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Product activation interrupted", ex);
            }
        }
    }

    public List<ProductLine> enabled() { return List.copyOf(products.keySet()); }
    public Map<ProductLine, String> errors() { return errors; }
    public void requireEnabled(ProductLine product) {
        if (product == null || !products.containsKey(product))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "product is not enabled: " + product);
    }
    public <T> T service(ProductLine product, Class<T> type) {
        requireEnabled(product);
        return products.get(product).context().getBean(type);
    }
    public LocalBusinessApi local(ProductLine product) { return service(product, LocalBusinessApi.class); }
    RequestMappingHandlerMapping internalMapping(ProductLine product) {
        requireEnabled(product);
        return products.get(product).mapping();
    }

    Map<String, Object> productProperties(ProductLine product) {
        var core = configuration.getCores().get(product);
        Map<String, Object> values = new HashMap<>();
        for (String prefix : List.of("surprising.account.kafka", "surprising.trading.order.kafka",
                "surprising.trading.trigger", "surprising.price.consumer", "surprising.websocket.kafka"))
            values.put(prefix + ".product-line", product.name());
        for (String prefix : List.of("surprising.account.aeron", "surprising.trading.order.aeron", "surprising.trading.trigger.aeron")) {
            values.put(prefix + ".hostnames", String.join(",", core.getHostnames()));
            values.put(prefix + ".egress-hostname", core.getEgressHostname());
            values.put(prefix + ".response-timeout", core.getResponseTimeout().toMillis() + "ms");
            values.put(prefix + ".client-connections", core.getClientConnections());
        }
        values.put("surprising.price.consumer.group-id", "gateway-mark-" + product + "-" + UUID.randomUUID());
        return values;
    }

    @Override @PreDestroy
    public synchronized void close() {
        closed = true;
        var contexts = new ArrayList<>(products.values());
        Collections.reverse(contexts);
        RuntimeException failure = null;
        for (var child : contexts) {
            try { child.context().close(); }
            catch (RuntimeException ex) {
                if (failure == null) failure = ex; else failure.addSuppressed(ex);
            }
        }
        products = Map.of();
        errors = Map.of();
        if (failure != null) throw failure;
    }

}
