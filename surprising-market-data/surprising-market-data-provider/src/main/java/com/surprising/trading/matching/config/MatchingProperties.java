package com.surprising.trading.matching.config;

import com.surprising.product.api.ProductLine;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.trading.matching")
public class MatchingProperties {

    private Kafka kafka = new Kafka();
    private Aeron aeron = new Aeron();

    @PostConstruct
    void validateProductLineConfiguration() {
        if (kafka.productLine == null) {
            throw new IllegalStateException("matching-market-data 必须显式配置 product-line");
        }
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka == null ? new Kafka() : kafka;
    }

    public Aeron getAeron() {
        return aeron;
    }

    public void setAeron(Aeron aeron) {
        this.aeron = aeron == null ? new Aeron() : aeron;
    }

    public static class Kafka {
        private ProductLine productLine;

        public ProductLine getProductLine() {
            return productLine;
        }

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine;
        }

    }

    public static class Aeron {
        private List<String> hostnames = List.of("localhost", "localhost", "localhost");
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private Duration bootstrapTimeout = Duration.ofSeconds(45);

        public List<String> getHostnames() { return hostnames; }
        public void setHostnames(List<String> hostnames) {
            if (hostnames == null || hostnames.size() != 3
                    || hostnames.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("Aeron hostnames must contain three members");
            }
            this.hostnames = List.copyOf(hostnames);
        }
        public String getEgressHostname() { return egressHostname; }
        public void setEgressHostname(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Aeron egress host is required");
            egressHostname = value.trim();
        }
        public Duration getResponseTimeout() { return responseTimeout; }
        public void setResponseTimeout(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("Aeron response timeout must be positive");
            }
            responseTimeout = value;
        }
        public Duration getBootstrapTimeout() { return bootstrapTimeout; }
        public void setBootstrapTimeout(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("Aeron bootstrap timeout must be positive");
            }
            bootstrapTimeout = value;
        }
    }
}
