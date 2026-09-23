package com.surprising.adl.provider.config;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@ConfigurationProperties(prefix = "surprising.adl")
public class AdlProperties {

    private Kafka kafka = new Kafka();
    @Setter
    private Scanner scanner = new Scanner();

    public void setKafka(Kafka kafka) {
        this.kafka = kafka == null ? new Kafka() : kafka;
    }

    @Getter
    public static class Kafka {
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        }

        public String getAccountType() {
            return productLine.accountTypeCode();
        }
    }

    @Getter
    @Setter
    public static class Scanner {
        private boolean enabled = true;
        private long scanDelayMs = 1000L;
        private long minDeficitAgeMs = 10_000L;
        private long maxMarkAgeMs = 5_000L;
        private int batchSize = 50;
        private int maxDeleveragesPerDeficit = 20;
        private int candidateMultiplier = 5;

    }
}
