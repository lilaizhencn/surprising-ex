package com.surprising.trading.matching.service;

import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.api.KafkaSymbolKeyValidator.SymbolKeyMismatchException;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.matching.config.MatchingProperties;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class MatchingCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(MatchingCommandConsumer.class);

    private final ObjectMapper objectMapper;
    private final MatchingService matchingService;
    private final MatchingPartitionAssignmentGuard partitionAssignmentGuard;
    private final MatchingProperties properties;

    public MatchingCommandConsumer(ObjectMapper objectMapper,
                                   MatchingService matchingService,
                                   MatchingPartitionAssignmentGuard partitionAssignmentGuard) {
        this(objectMapper, matchingService, partitionAssignmentGuard, new MatchingProperties());
    }

    @Autowired
    public MatchingCommandConsumer(ObjectMapper objectMapper,
                                   MatchingService matchingService,
                                   MatchingPartitionAssignmentGuard partitionAssignmentGuard,
                                   MatchingProperties properties) {
        this.objectMapper = objectMapper;
        this.matchingService = matchingService;
        this.partitionAssignmentGuard = partitionAssignmentGuard;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.orderCommandsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "matchingKafkaListenerContainerFactory")
    public void onCommands(List<ConsumerRecord<String, String>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<OrderCommandEvent> commands = new ArrayList<>(records.size());
        boolean processingStarted = false;
        try {
            for (ConsumerRecord<String, String> record : records) {
                OrderCommandEvent command = objectMapper.readValue(record.value(), OrderCommandEvent.class);
                KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), command.symbol(), "order command");
                requireCurrentProductTopic(record.topic());
                commands.add(command);
            }
            for (ConsumerRecord<String, String> record : records) {
                partitionAssignmentGuard.recordProcessedCommand(record.topic(), record.partition());
            }
            // Kafka preserves record order inside every symbol-keyed partition. The bounded poll is committed only
            // after one database transaction persists all matching results, order transitions, trades and Outbox rows.
            processingStarted = true;
            matchingService.processBatch(commands);
        } catch (SymbolKeyMismatchException ex) {
            log.error("Rejected matching command with invalid Kafka key: {}", ex.getMessage());
            throw new IllegalStateException("failed to process matching command batch", ex);
        } catch (ProductTopicMismatchException ex) {
            log.error("Rejected matching command from invalid product topic: {}", ex.getMessage());
            throw new IllegalStateException("failed to process matching command batch", ex);
        } catch (Exception ex) {
            log.error("Failed to process matching command batch: {}", ex.getMessage(), ex);
            if (processingStarted) {
                OrderCommandEvent first = commands.get(0);
                OrderCommandEvent last = commands.get(commands.size() - 1);
                partitionAssignmentGuard.requestRestart("matching command batch failed after processing started; "
                        + "restart is required before Kafka replay to avoid using a mutated exchange-core book. "
                        + "batchSize=" + commands.size()
                        + " firstCommandId=" + first.commandId()
                        + " firstOrderId=" + first.orderId()
                        + " firstSymbol=" + first.symbol()
                        + " lastCommandId=" + last.commandId());
            }
            throw new IllegalStateException("failed to process matching command batch", ex);
        }
    }

    public String orderCommandsTopic() {
        return properties.getKafka().getOrderCommandsTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }

    private void requireCurrentProductTopic(String topic) {
        MatchingProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        String expectedTopic = kafka.getOrderCommandsTopic();
        if (!expectedTopic.equals(topic)) {
            throw new ProductTopicMismatchException("order command topic must match current product line: expected="
                    + expectedTopic + " actual=" + topic);
        }
    }

    private static final class ProductTopicMismatchException extends RuntimeException {
        private ProductTopicMismatchException(String message) {
            super(message);
        }
    }
}
