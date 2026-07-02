package com.surprising.account.provider.service;

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

    public MatchTradeConsumer(ObjectMapper objectMapper, AccountService accountService) {
        this(objectMapper, accountService, AccountSettlementMetrics.noop());
    }

    @Autowired
    public MatchTradeConsumer(ObjectMapper objectMapper,
                              AccountService accountService,
                              AccountSettlementMetrics settlementMetrics) {
        this.objectMapper = objectMapper;
        this.accountService = accountService;
        this.settlementMetrics = settlementMetrics;
    }

    @KafkaListener(
            topics = "${surprising.account.kafka.match-trades-topic}",
            groupId = "${surprising.account.kafka.group-id}",
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
            boolean processed = accountService.processTradeIfNew(trade);
            settlementMetrics.recordSuccess(trade.eventTime(), startedAtNanos, processed);
        } catch (Exception ex) {
            settlementMetrics.recordFailure(trade == null ? null : trade.eventTime(), startedAtNanos);
            log.error("Failed to process match trade: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process match trade", ex);
        }
    }
}
