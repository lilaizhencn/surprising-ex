package com.surprising.funding.provider.service;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.price.api.model.PerpFundingRateEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Rehydrates the latest predicted rate after node failover; no database prediction lookup is needed. */
@Component
public class FundingRateKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(FundingRateKafkaConsumer.class);

    private final ObjectMapper objectMapper;
    private final LatestFundingRateCache cache;
    private final FundingProperties properties;

    public FundingRateKafkaConsumer(ObjectMapper objectMapper,
                                    LatestFundingRateCache cache,
                                    FundingProperties properties) {
        this.objectMapper = objectMapper;
        this.cache = cache;
        this.properties = properties;
    }

    @KafkaListener(topics = "#{__listener.fundingRateTopic()}", groupId = "#{__listener.groupId()}",
            containerFactory = "fundingRateCacheKafkaListenerContainerFactory")
    public void onFundingRate(ConsumerRecord<String, String> record) {
        try {
            PerpFundingRateEvent event = objectMapper.readValue(record.value(), PerpFundingRateEvent.class);
            if (record.key() == null || !record.key().equals(event.symbol())) {
                throw new IllegalArgumentException("funding rate Kafka key must match payload symbol");
            }
            cache.update(event);
        } catch (Exception ex) {
            log.error("Failed to cache funding rate topic={} partition={} offset={}: {}",
                    record.topic(), record.partition(), record.offset(), ex.getMessage(), ex);
            throw new IllegalStateException("failed to cache funding rate", ex);
        }
    }

    public String fundingRateTopic() {
        return properties.getKafka().getFundingRateTopic();
    }

    public String groupId() {
        return properties.getKafka().getCacheGroupId();
    }
}
