package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.api.model.MatchTradeEvent;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class MatchTradeConsumer {

    private static final Logger log = LoggerFactory.getLogger(MatchTradeConsumer.class);

    private final ObjectMapper objectMapper;
    private final AccountService accountService;
    private final AccountSettlementMetrics settlementMetrics;
    private final AccountProperties properties;
    private final AccountSettlementConcurrencyGuard concurrencyGuard;

    public MatchTradeConsumer(ObjectMapper objectMapper, AccountService accountService) {
        this(objectMapper, accountService, AccountSettlementMetrics.noop());
    }

    public MatchTradeConsumer(ObjectMapper objectMapper,
                              AccountService accountService,
                              AccountSettlementMetrics settlementMetrics) {
        this(objectMapper, accountService, settlementMetrics, new AccountProperties());
    }

    public MatchTradeConsumer(ObjectMapper objectMapper,
                              AccountService accountService,
                              AccountSettlementMetrics settlementMetrics,
                              AccountProperties properties) {
        this(objectMapper, accountService, settlementMetrics, properties,
                new AccountSettlementConcurrencyGuard(properties));
    }

    @Autowired
    public MatchTradeConsumer(ObjectMapper objectMapper,
                              AccountService accountService,
                              AccountSettlementMetrics settlementMetrics,
                              AccountProperties properties,
                              AccountSettlementConcurrencyGuard concurrencyGuard) {
        this.objectMapper = objectMapper;
        this.accountService = accountService;
        this.settlementMetrics = settlementMetrics;
        this.properties = properties;
        this.concurrencyGuard = concurrencyGuard;
    }

    @KafkaListener(
            topics = "#{__listener.matchTradesTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "accountKafkaListenerContainerFactory")
    public void onTrades(List<ConsumerRecord<String, String>> records) {
        for (ConsumerRecord<String, String> record : records) {
            onTrade(record);
        }
    }

    public void onTrade(ConsumerRecord<String, String> record) {
        long startedAtNanos = System.nanoTime();
        MatchTradeEvent trade = null;
        try {
            trade = objectMapper.readValue(record.value(), MatchTradeEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), trade.symbol(), "match trade");
            requireCurrentProductTopic(record.topic());
            MatchTradeEvent settledTrade = trade;
            long lockWaitStartedAtNanos = System.nanoTime();
            boolean processed = concurrencyGuard.withTradeUserLocks(settledTrade,
                    () -> {
                        settlementMetrics.recordUserLockWait(lockWaitStartedAtNanos);
                        return accountService.processTradeIfNew(settledTrade);
                    });
            settlementMetrics.recordSuccess(trade.eventTime(), startedAtNanos, processed);
        } catch (Exception ex) {
            settlementMetrics.recordFailure(trade == null ? null : trade.eventTime(), startedAtNanos);
            log.error("Failed to process match trade: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process match trade", ex);
        }
    }

    public String matchTradesTopic() {
        return properties.getKafka().getMatchTradesTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }

    private void requireCurrentProductTopic(String topic) {
        AccountProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        String expectedTopic = kafka.getMatchTradesTopic();
        if (!expectedTopic.equals(topic)) {
            throw new ProductTopicMismatchException("match trade topic must match current product line: expected="
                    + expectedTopic + " actual=" + topic);
        }
    }

    private static final class ProductTopicMismatchException extends RuntimeException {
        private ProductTopicMismatchException(String message) {
            super(message);
        }
    }
}
