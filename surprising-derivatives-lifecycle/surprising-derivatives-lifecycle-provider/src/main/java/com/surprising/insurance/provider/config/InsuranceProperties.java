package com.surprising.insurance.provider.config;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;

@Getter
public class InsuranceProperties {

    private Kafka kafka = new Kafka();
    @Setter
    private volatile Coverage coverage = new Coverage();

    public void setKafka(Kafka kafka) {
        this.kafka = kafka == null ? new Kafka() : kafka;
    }

    @Getter
    public static class Kafka {
        @Setter
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        @Setter
        private int concurrency = 2;
        @Setter
        private int maxPollRecords = 500;

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        }

        public String getGroupId() {
            return productTopics().consumerGroup("insurance");
        }


        public String getLiquidationFeeEventsTopic() {
            return productTopics().accountLiquidationFeeEventsTopic();
        }


        public String getInstrumentSnapshotGroupId() {
            return "surprising-" + productLine.topicSegment() + "-insurance-instrument-snapshot-v1";
        }

        public String getUserCommandsTopic() {
            return productTopics().accountUserCommandsTopic();
        }

        public String getAccountType() {
            return productLine.accountTypeCode();
        }

        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
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
