package com.surprising.account.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import java.util.UUID;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.account")
public class AccountProperties {

    private Kafka kafka = new Kafka();
    private Aeron aeron = new Aeron();
    private String internalServiceSecret = "";

    /** 启动时拒绝未隔离的旧版 Topic 配置。 */
    @PostConstruct
    void validateProductLineConfiguration() {
        ProductLineConfiguration.require(kafka.productLine, kafka.productTopicsEnabled, "account");
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }


    public Aeron getAeron() {
        return aeron;
    }

    public void setAeron(Aeron aeron) {
        this.aeron = aeron == null ? new Aeron() : aeron;
    }

    public String getInternalServiceSecret() {
        return internalServiceSecret;
    }

    public void setInternalServiceSecret(String internalServiceSecret) {
        this.internalServiceSecret = internalServiceSecret;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        /** 必须由部署配置显式指定，禁止缺省落到永续产品线。 */
        private ProductLine productLine;
        private boolean productTopicsEnabled;
        private String groupId = "surprising-account-v1";
        /** 每个账户 JVM 的实例标识；快照广播消费组必须按实例隔离。 */
        private String clientId = "account-provider-" + UUID.randomUUID();
        private String orderCommandsTopic = "surprising.perp.order.commands.v1";
        private String orderEventsTopic = "surprising.perp.order.events.v1";
        private String positionEventsTopic = "surprising.account.position.events.v1";
        private String openInterestEventsTopic = "surprising.account.open-interest.events.v1";
        private String liquidationFeeEventsTopic = "surprising.account.liquidation-fee.events.v1";
        private String accountStateEventsTopic = "surprising.account.state.events.v1";
        private String triggerOrderEventsTopic = "surprising.perp.trigger-order.events.v1";
        private String deliverySettlementsTopic = "surprising.linear-delivery.delivery.settlements.v1";
        private String optionExercisesTopic = "surprising.option.option.exercises.v1";
        private String instrumentLifecycleDrainTopic = "surprising.instrument.lifecycle-drain.v1";
        private int concurrency = 2;
        private int maxPollRecords = 500;

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public ProductLine getProductLine() {
            return productLine;
        }

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine;
        }

        public boolean isProductTopicsEnabled() {
            return productTopicsEnabled;
        }

        public void setProductTopicsEnabled(boolean productTopicsEnabled) {
            this.productTopicsEnabled = productTopicsEnabled;
        }

        public String getGroupId() {
            return productTopics().consumerGroup("account");
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getClientId() {
            return clientId;
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

        public void setOrderCommandsTopic(String orderCommandsTopic) {
            this.orderCommandsTopic = orderCommandsTopic;
        }

        public String getOrderEventsTopic() {
            return productTopics().orderEventsTopic();
        }

        public void setOrderEventsTopic(String orderEventsTopic) {
            this.orderEventsTopic = orderEventsTopic;
        }

        public String getPositionEventsTopic() {
            return productTopics().accountPositionEventsTopic();
        }

        public void setPositionEventsTopic(String positionEventsTopic) {
            this.positionEventsTopic = positionEventsTopic;
        }

        public String getOpenInterestEventsTopic() {
            return productTopics().accountOpenInterestEventsTopic();
        }

        public void setOpenInterestEventsTopic(String openInterestEventsTopic) {
            this.openInterestEventsTopic = openInterestEventsTopic;
        }

        public String getLiquidationFeeEventsTopic() {
            return productTopics().accountLiquidationFeeEventsTopic();
        }

        public void setLiquidationFeeEventsTopic(String liquidationFeeEventsTopic) {
            this.liquidationFeeEventsTopic = liquidationFeeEventsTopic;
        }

        public String getAccountStateEventsTopic() {
            return productTopics().accountStateEventsTopic();
        }

        public void setAccountStateEventsTopic(String accountStateEventsTopic) {
            this.accountStateEventsTopic = accountStateEventsTopic;
        }


        public String getTriggerOrderEventsTopic() {
            return productTopics().triggerOrderEventsTopic();
        }

        public void setTriggerOrderEventsTopic(String triggerOrderEventsTopic) {
            this.triggerOrderEventsTopic = triggerOrderEventsTopic;
        }

        public String getDeliverySettlementsTopic() {
            return productTopics().deliverySettlementsTopic();
        }

        public void setDeliverySettlementsTopic(String deliverySettlementsTopic) {
            this.deliverySettlementsTopic = deliverySettlementsTopic;
        }

        public boolean isDeliverySettlementsTopicEnabled() {
            return productLine == ProductLine.LINEAR_DELIVERY
                    || productLine == ProductLine.INVERSE_DELIVERY;
        }

        public String getOptionExercisesTopic() {
            return productTopics().optionExercisesTopic();
        }

        public void setOptionExercisesTopic(String optionExercisesTopic) {
            this.optionExercisesTopic = optionExercisesTopic;
        }

        public boolean isOptionExercisesTopicEnabled() {
            return productLine.isOptionProduct();
        }

        public String getInstrumentLifecycleDrainTopic() {
            return instrumentLifecycleDrainTopic;
        }

        public void setInstrumentLifecycleDrainTopic(String instrumentLifecycleDrainTopic) {
            this.instrumentLifecycleDrainTopic = instrumentLifecycleDrainTopic;
        }

        public String getInstrumentLifecycleGroupId() {
            return productTopics().consumerGroup("account-instrument-lifecycle");
        }
        public String getInstrumentSnapshotGroupId() {
            return productTopics().consumerGroup("account-instrument-snapshot");
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getMaxPollRecords() {
            return maxPollRecords;
        }

        public void setMaxPollRecords(int maxPollRecords) {
            this.maxPollRecords = maxPollRecords;
        }

        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }
    }

    public static class Aeron {
        private String sourceIdentity = "account-provider-node";

        public String getSourceIdentity() {
            return sourceIdentity;
        }

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

        public java.util.List<String> getHostnames() { return hostnames; }
        public void setHostnames(java.util.List<String> hostnames) {
            if (hostnames == null || hostnames.size() != 3 || hostnames.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("aeron hostnames must contain three non-blank members");
            }
            this.hostnames = java.util.List.copyOf(hostnames);
        }
        public String getEgressHostname() { return egressHostname; }
        public void setEgressHostname(String egressHostname) {
            if (egressHostname == null || egressHostname.isBlank()) throw new IllegalArgumentException("aeron egress hostname is required");
            this.egressHostname = egressHostname.trim();
        }
        public Duration getResponseTimeout() { return responseTimeout; }
        public void setResponseTimeout(Duration responseTimeout) {
            if (responseTimeout == null || responseTimeout.isZero() || responseTimeout.isNegative()) throw new IllegalArgumentException("aeron response timeout must be positive");
            this.responseTimeout = responseTimeout;
        }
        public int getClientConnections() { return clientConnections; }
        public void setClientConnections(int clientConnections) {
            if (clientConnections < 1 || clientConnections > 64) throw new IllegalArgumentException("aeron client connections must be in [1,64]");
            this.clientConnections = clientConnections;
        }
    }

}
