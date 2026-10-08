package com.surprising.gateway.provider.product;

import com.surprising.product.api.ProductLine;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 部署拓扑：启用的产品线及其核心地址；不承载合约、费率等业务配置。 */
@ConfigurationProperties("surprising.gateway.products")
public class GatewayProductsProperties {
    private List<ProductLine> enabled = List.of();
    private Map<ProductLine, Core> cores = new EnumMap<>(ProductLine.class);

    public List<ProductLine> getEnabled() { return enabled; }
    public void setEnabled(List<ProductLine> enabled) {
        this.enabled = enabled == null ? List.of() : List.copyOf(enabled);
    }
    public Map<ProductLine, Core> getCores() { return cores; }
    public void setCores(Map<ProductLine, Core> cores) {
        this.cores = new EnumMap<>(ProductLine.class);
        if (cores != null) this.cores.putAll(cores);
    }
    @PostConstruct
    public void validate() {
        if (enabled.isEmpty() || enabled.stream().distinct().count() != enabled.size())
            throw new IllegalArgumentException("GATEWAY_PRODUCT_LINES must specify distinct enabled products");
        for (ProductLine product : enabled) {
            Core core = cores.get(product);
            if (core == null) throw new IllegalArgumentException("missing Gateway Core connection: " + product);
            core.validate();
        }
    }
    public void requireEnabled(ProductLine product) {
        if (product == null || !enabled.contains(product))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "product is not enabled: " + product);
    }
    public static class Core {
        private List<String> hostnames = List.of("localhost");
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int clientConnections = 4;
        public List<String> getHostnames() { return hostnames; }
        public void setHostnames(List<String> hostnames) { this.hostnames = hostnames == null ? List.of() : List.copyOf(hostnames); }
        public String getEgressHostname() { return egressHostname; }
        public void setEgressHostname(String value) { egressHostname = value; }
        public Duration getResponseTimeout() { return responseTimeout; }
        public void setResponseTimeout(Duration value) { responseTimeout = value; }
        public int getClientConnections() { return clientConnections; }
        public void setClientConnections(int value) { clientConnections = value; }
        void validate() {
            if ((hostnames.size() != 1 && hostnames.size() != 3) || hostnames.stream().anyMatch(String::isBlank)
                    || egressHostname == null || egressHostname.isBlank() || responseTimeout == null
                    || responseTimeout.compareTo(Duration.ofMillis(1)) < 0 || responseTimeout.compareTo(Duration.ofMinutes(1)) > 0
                    || clientConnections < 1 || clientConnections > 64)
                throw new IllegalArgumentException("invalid Gateway Core connection configuration");
        }
    }
}
