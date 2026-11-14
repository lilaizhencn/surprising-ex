package com.surprising.insurance.provider.config;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;

@Getter
public class InsuranceProperties {

    private Kafka kafka = new Kafka();
    @Setter
    private volatile Coverage coverage = new Coverage();

    public ProductLine getProductLine() {
        return kafka.getProductLine();
    }

    public void setProductLine(ProductLine productLine) {
        kafka.setProductLine(productLine);
    }

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
    public static class Coverage {
        private boolean enabled = true;
        private long scanDelayMs = 1000L;
        private int batchSize = 100;

    }
}
