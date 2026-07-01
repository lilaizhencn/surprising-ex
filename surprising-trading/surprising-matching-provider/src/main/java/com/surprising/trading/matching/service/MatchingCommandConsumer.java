package com.surprising.trading.matching.service;

import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.api.KafkaSymbolKeyValidator.SymbolKeyMismatchException;
import com.surprising.trading.api.model.OrderCommandEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.locks.ReentrantLock;

@Service
public class MatchingCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(MatchingCommandConsumer.class);
    private static final int SYMBOL_LOCK_STRIPES = 1024;

    private final ObjectMapper objectMapper;
    private final MatchingService matchingService;
    private final MatchingPartitionAssignmentGuard partitionAssignmentGuard;
    private final ReentrantLock[] symbolLocks = new ReentrantLock[SYMBOL_LOCK_STRIPES];

    public MatchingCommandConsumer(ObjectMapper objectMapper,
                                   MatchingService matchingService,
                                   MatchingPartitionAssignmentGuard partitionAssignmentGuard) {
        this.objectMapper = objectMapper;
        this.matchingService = matchingService;
        this.partitionAssignmentGuard = partitionAssignmentGuard;
        for (int i = 0; i < symbolLocks.length; i++) {
            symbolLocks[i] = new ReentrantLock();
        }
    }

    @KafkaListener(
            topics = "${surprising.trading.matching.kafka.order-commands-topic}",
            groupId = "${surprising.trading.matching.kafka.group-id}",
            containerFactory = "matchingKafkaListenerContainerFactory")
    public void onCommand(ConsumerRecord<String, String> record) {
        OrderCommandEvent command = null;
        try {
            String payload = record.value();
            command = objectMapper.readValue(payload, OrderCommandEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), command.symbol(), "order command");
            partitionAssignmentGuard.recordProcessedCommand(record.topic(), record.partition());
            ReentrantLock lock = symbolLock(command.symbol());
            lock.lock();
            try {
                // The lock is outside the transactional service proxy, so the DB commit completes
                // before another command for the same symbol can produce the next depth sequence.
                matchingService.process(command);
            } finally {
                lock.unlock();
            }
        } catch (SymbolKeyMismatchException ex) {
            log.error("Rejected matching command with invalid Kafka key: {}", ex.getMessage());
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

    private ReentrantLock symbolLock(String symbol) {
        return symbolLocks[Math.floorMod(symbol.hashCode(), symbolLocks.length)];
    }
}
