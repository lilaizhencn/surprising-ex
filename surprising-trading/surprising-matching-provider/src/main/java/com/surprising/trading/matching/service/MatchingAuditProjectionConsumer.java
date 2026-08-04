package com.surprising.trading.matching.service;

import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.api.KafkaSymbolKeyValidator.SymbolKeyMismatchException;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.repository.MatchingResultRepository;
import com.surprising.trading.matching.repository.MatchingTradeRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class MatchingAuditProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(MatchingAuditProjectionConsumer.class);

    private final ObjectMapper objectMapper;
    private final MatchingProperties properties;
    private final MatchingResultRepository resultRepository;
    private final MatchingTradeRepository tradeRepository;

    public MatchingAuditProjectionConsumer(ObjectMapper objectMapper,
                                           MatchingProperties properties,
                                           MatchingResultRepository resultRepository,
                                           MatchingTradeRepository tradeRepository) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.resultRepository = resultRepository;
        this.tradeRepository = tradeRepository;
    }

    @KafkaListener(
            topics = "#{__listener.matchResultsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "matchingInstrumentSnapshotKafkaListenerContainerFactory")
    public void onMatchResult(ConsumerRecord<String, String> record) {
        try {
            MatchResultEvent result = objectMapper.readValue(record.value(), MatchResultEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), result.symbol(), "match result");
            requireCurrentProductTopic(record.topic());
            resultRepository.save(result);
            tradeRepository.saveBatch(result.trades());
        } catch (SymbolKeyMismatchException ex) {
            log.error("Rejected match result with invalid Kafka key: {}", ex.getMessage());
            throw new IllegalStateException("failed to project match result", ex);
        } catch (Exception ex) {
            log.error("Failed to project match result: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to project match result", ex);
        }
    }

    public String matchResultsTopic() {
        return properties.getKafka().getMatchResultsTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId() + "-audit";
    }

    private void requireCurrentProductTopic(String topic) {
        MatchingProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        String expectedTopic = kafka.getMatchResultsTopic();
        if (!expectedTopic.equals(topic)) {
            throw new IllegalStateException("match result topic must match current product line: expected="
                    + expectedTopic + " actual=" + topic);
        }
    }
}
