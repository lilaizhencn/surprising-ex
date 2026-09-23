package com.surprising.account.provider.config;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import java.util.UUID;
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



    @Getter
    public static class Kafka {
        @Setter
        private String bootstrapServers = "localhost:9092";
        /** 必须由部署配置显式指定，禁止缺省落到永续产品线。 */
        @Setter
        private ProductLine productLine;
        /** 账户 Kafka 客户端及事务 producer 前缀使用的实例标识。 */
        private String clientId = "account-provider-" + UUID.randomUUID();
        @Setter
        private String instrumentLifecycleDrainTopic = "surprising.instrument.lifecycle-drain.v1";
        @Setter
        private int maxPollRecords = 500;

        public String getGroupId() {
            return productTopics().consumerGroup("account");
        }


        public void setClientId(String clientId) {
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("账户节点 clientId 不能为空");
            }
            this.clientId = clientId.trim();
        }

        public String getOrderCommandsTopic() {
            return productTopics().orderCommandsTopic();
        }


        public String getOrderEventsTopic() {
            return productTopics().orderEventsTopic();
        }


        public String getPositionEventsTopic() {
            return productTopics().accountPositionEventsTopic();
        }


        public String getOpenInterestEventsTopic() {
            return productTopics().accountOpenInterestEventsTopic();
        }


        public String getLiquidationFeeEventsTopic() {
            return productTopics().accountLiquidationFeeEventsTopic();
        }


        public String getAccountStateEventsTopic() {
            return productTopics().accountStateEventsTopic();
        }



        public String getTriggerOrderEventsTopic() {
            return productTopics().triggerOrderEventsTopic();
        }


        public String getDeliverySettlementsTopic() {
            return productTopics().deliverySettlementsTopic();
        }


        public boolean isDeliverySettlementsTopicEnabled() {
            return productLine == ProductLine.LINEAR_DELIVERY
                    || productLine == ProductLine.INVERSE_DELIVERY;
        }

        public String getOptionExercisesTopic() {
            return productTopics().optionExercisesTopic();
        }


        public boolean isOptionExercisesTopicEnabled() {
            return productLine.isOptionProduct();
        }

        public String getInstrumentLifecycleGroupId() {
            return productTopics().consumerGroup("account-instrument-lifecycle");
        }


        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }
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
