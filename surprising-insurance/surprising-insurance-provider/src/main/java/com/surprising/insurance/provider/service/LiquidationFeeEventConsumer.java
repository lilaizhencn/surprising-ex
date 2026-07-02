package com.surprising.insurance.provider.service;

import com.surprising.account.api.model.LiquidationFeeSettledEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class LiquidationFeeEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(LiquidationFeeEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final InsuranceService insuranceService;

    public LiquidationFeeEventConsumer(ObjectMapper objectMapper, InsuranceService insuranceService) {
        this.objectMapper = objectMapper;
        this.insuranceService = insuranceService;
    }

    @KafkaListener(
            topics = "${surprising.insurance.kafka.liquidation-fee-events-topic}",
            groupId = "${surprising.insurance.kafka.group-id}",
            containerFactory = "insuranceKafkaListenerContainerFactory")
    public void onLiquidationFee(ConsumerRecord<String, String> record) {
        try {
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
}
