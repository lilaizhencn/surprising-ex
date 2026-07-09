package com.surprising.insurance.provider.service;

import com.surprising.account.api.model.LiquidationFeeSettledEvent;
import com.surprising.insurance.provider.config.InsuranceProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class LiquidationFeeEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(LiquidationFeeEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final InsuranceService insuranceService;
    private final InsuranceProperties properties;

    public LiquidationFeeEventConsumer(ObjectMapper objectMapper, InsuranceService insuranceService) {
        this(objectMapper, insuranceService, new InsuranceProperties());
    }

    @Autowired
    public LiquidationFeeEventConsumer(ObjectMapper objectMapper,
                                       InsuranceService insuranceService,
                                       InsuranceProperties properties) {
        this.objectMapper = objectMapper;
        this.insuranceService = insuranceService;
        this.properties = properties == null ? new InsuranceProperties() : properties;
    }

    @KafkaListener(
            topics = "#{__listener.liquidationFeeEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "insuranceKafkaListenerContainerFactory")
    public void onLiquidationFee(ConsumerRecord<String, String> record) {
        try {
            requireCurrentProductTopic(record.topic());
            LiquidationFeeSettledEvent event = objectMapper.readValue(record.value(),
                    LiquidationFeeSettledEvent.class);
            requireAssetKey(record.key(), event.asset());
            insuranceService.collectLiquidationFee(event);
        } catch (Exception ex) {
            log.error("Failed to process liquidation fee event: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process liquidation fee event", ex);
        }
    }

    private void requireAssetKey(String key, String asset) {
        if (key == null || !key.equals(asset)) {
            throw new IllegalArgumentException("liquidation fee event key must match asset");
        }
    }

    private void requireCurrentProductTopic(String topic) {
        InsuranceProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        String expectedTopic = kafka.getLiquidationFeeEventsTopic();
        if (!expectedTopic.equals(topic)) {
            throw new ProductTopicMismatchException("liquidation fee topic must match current product line: expected="
                    + expectedTopic + " actual=" + topic);
        }
    }

    public String liquidationFeeEventsTopic() {
        return properties.getKafka().getLiquidationFeeEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }

    private static final class ProductTopicMismatchException extends RuntimeException {
        private ProductTopicMismatchException(String message) {
            super(message);
        }
    }
}
