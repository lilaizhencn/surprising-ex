package com.surprising.gateway.provider.product;

import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Core 连接部署参数；产品线启用状态只从数据库读取。 */
@ConfigurationProperties("surprising.gateway.products")
public class GatewayProductsProperties {
    private Map<ProductLine, Core> cores = new EnumMap<>(ProductLine.class);

    public Map<ProductLine, Core> getCores() { return cores; }
    public void setCores(Map<ProductLine, Core> cores) {
        this.cores = new EnumMap<>(ProductLine.class);
        if (cores != null) this.cores.putAll(cores);
    }
    public void validate(ProductLine product) {
        Core core = cores.get(product);
        if (core == null) throw new IllegalArgumentException("missing Gateway Core connection: " + product);
        core.validate();
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
