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
    private final Map<ProductLine, AnnotationConfigApplicationContext> products = new EnumMap<>(ProductLine.class);
    private final Map<ProductLine, RequestMappingHandlerMapping> internalMappings = new EnumMap<>(ProductLine.class);
    private final GatewayProductsProperties configuration;

    public GatewayProductServices(ConfigurableApplicationContext parent, GatewayProductsProperties configuration) {
        this.configuration = configuration;
        configuration.validate();
        try {
            for (ProductLine product : configuration.getEnabled()) {
                var child = new AnnotationConfigApplicationContext();
                products.put(product, child); // 包括 refresh 失败的产品，确保连接和消费者也被关闭。
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
                internalMappings.put(product, mapping);
            }
        } catch (RuntimeException | Error failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public List<ProductLine> enabled() { return configuration.getEnabled(); }
    public void requireEnabled(ProductLine product) { configuration.requireEnabled(product); }
    public <T> T service(ProductLine product, Class<T> type) {
        requireEnabled(product);
        return products.get(product).getBean(type);
    }
    public LocalBusinessApi local(ProductLine product) { return service(product, LocalBusinessApi.class); }
    RequestMappingHandlerMapping internalMapping(ProductLine product) {
        requireEnabled(product);
        return internalMappings.get(product);
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
    public void close() {
        var contexts = new ArrayList<>(products.values());
        Collections.reverse(contexts);
        RuntimeException failure = null;
        for (var child : contexts) {
            try { child.close(); }
            catch (RuntimeException ex) {
                if (failure == null) failure = ex; else failure.addSuppressed(ex);
            }
        }
        products.clear();
        internalMappings.clear();
        if (failure != null) throw failure;
    }

}
