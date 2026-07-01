package com.surprising.account.provider.service;

import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.api.model.MatchTradeEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class MatchTradeConsumer {

    private static final Logger log = LoggerFactory.getLogger(MatchTradeConsumer.class);

    private final ObjectMapper objectMapper;
    private final AccountService accountService;

    public MatchTradeConsumer(ObjectMapper objectMapper, AccountService accountService) {
        this.objectMapper = objectMapper;
        this.accountService = accountService;
    }

    @KafkaListener(
            topics = "${surprising.account.kafka.match-trades-topic}",
            groupId = "${surprising.account.kafka.group-id}",
            containerFactory = "accountKafkaListenerContainerFactory")
    public void onTrade(ConsumerRecord<String, String> record) {
        try {
            MatchTradeEvent trade = objectMapper.readValue(record.value(), MatchTradeEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), trade.symbol(), "match trade");
            accountService.processTrade(trade);
        } catch (Exception ex) {
            log.error("Failed to process match trade: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process match trade", ex);
        }
    }
}
