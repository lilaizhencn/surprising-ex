package com.surprising.trading.matching.service;

import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.api.KafkaSymbolKeyValidator.SymbolKeyMismatchException;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.matching.config.MatchingProperties;
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
    public void onCommand(ConsumerRecord<String, String> record) {
        OrderCommandEvent command = null;
        try {
            String payload = record.value();
            command = objectMapper.readValue(payload, OrderCommandEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), command.symbol(), "order command");
            requireCurrentProductTopic(record.topic());
            partitionAssignmentGuard.recordProcessedCommand(record.topic(), record.partition());
            // The symbol is the validated Kafka key, so all commands for one book are in one partition.
            // A Kafka partition is processed serially by exactly one listener thread; process() returns only
            // after its transactional proxy commits. A local striped lock would only serialize unrelated
            // symbols that collide in the stripe and cannot improve cross-node ordering.
            matchingService.process(command);
        } catch (SymbolKeyMismatchException ex) {
            log.error("Rejected matching command with invalid Kafka key: {}", ex.getMessage());
            throw new IllegalStateException("failed to process matching command", ex);
        } catch (ProductTopicMismatchException ex) {
            log.error("Rejected matching command from invalid product topic: {}", ex.getMessage());
            throw new IllegalStateException("failed to process matching command", ex);
        } catch (Exception ex) {
            log.error("Failed to process matching command: {}", ex.getMessage(), ex);
            if (command != null) {
                partitionAssignmentGuard.requestRestart("matching command failed after payload decode; "
                        + "restart is required before Kafka replay to avoid using a mutated exchange-core book. "
                        + "commandId=" + command.commandId()
                        + " orderId=" + command.orderId()
                        + " symbol=" + command.symbol());
            }
            throw new IllegalStateException("failed to process matching command", ex);
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
