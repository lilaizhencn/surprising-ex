package com.surprising.account.provider.config;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@ConfigurationProperties(prefix = "surprising.account")
public class AccountProperties {

    @Setter
    private Kafka kafka = new Kafka();
    private Aeron aeron = new Aeron();

    public void setAeron(Aeron aeron) {
        this.aeron = aeron == null ? new Aeron() : aeron;
    }

    public ProductLine getProductLine() {
        return kafka.getProductLine();
    }

    public void setProductLine(ProductLine productLine) {
        kafka.setProductLine(productLine);
    }

    @Getter
    public static class Kafka {
        /** 必须由部署配置显式指定，禁止缺省落到永续产品线。 */
        @Setter
        private ProductLine productLine;
    }

    @Getter
    public static class Aeron {
        private String sourceIdentity = "account-provider-node";

        public void setSourceIdentity(String sourceIdentity) {
            if (sourceIdentity == null || sourceIdentity.isBlank()) {
                throw new IllegalArgumentException("账户 Aeron sourceIdentity 不能为空");
            }
            this.sourceIdentity = sourceIdentity.trim();
        }
        private java.util.List<String> hostnames = java.util.List.of("localhost", "localhost", "localhost");
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int clientConnections = 4;

        public void setHostnames(java.util.List<String> hostnames) {
            if (hostnames == null || (hostnames.size() != 1 && hostnames.size() != 3)
                    || hostnames.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("aeron hostnames must contain one or three non-blank members");
            }
            this.hostnames = java.util.List.copyOf(hostnames);
        }
        public void setEgressHostname(String egressHostname) {
            if (egressHostname == null || egressHostname.isBlank()) throw new IllegalArgumentException("aeron egress hostname is required");
            this.egressHostname = egressHostname.trim();
        }
        public void setResponseTimeout(Duration responseTimeout) {
            if (responseTimeout == null || responseTimeout.isZero() || responseTimeout.isNegative()) throw new IllegalArgumentException("aeron response timeout must be positive");
            this.responseTimeout = responseTimeout;
        }
        public void setClientConnections(int clientConnections) {
            if (clientConnections < 1 || clientConnections > 64) throw new IllegalArgumentException("aeron client connections must be in [1,64]");
            this.clientConnections = clientConnections;
        }
    }

}
