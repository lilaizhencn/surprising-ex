package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderSide;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MatchTradeConsumerTest {

    private static final MatchTradeEvent TRADE = new MatchTradeEvent(
            9201L,
            9101L,
            "BTC-USDT",
            9002L,
            1L,
            2002L,
            OrderSide.BUY,
            9001L,
            1L,
            1001L,
            600_000L,
            3L,
            true,
            false,
            Instant.parse("2026-07-01T00:00:00Z"));

    @Test
    void processesTradeWhenKafkaKeyMatchesPayloadSymbol() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingAccountService accountService = new RecordingAccountService();
        MatchTradeConsumer consumer = new MatchTradeConsumer(objectMapper, accountService);

        consumer.onTrade(new ConsumerRecord<>("surprising.perp.match.trades.v1", 1, 10L,
                "BTC-USDT", objectMapper.writeValueAsString(TRADE)));

        assertThat(accountService.processed).isEqualTo(TRADE);
    }

    @Test
    void rejectsTradeWhenKafkaKeyDoesNotMatchPayloadSymbol() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingAccountService accountService = new RecordingAccountService();
        MatchTradeConsumer consumer = new MatchTradeConsumer(objectMapper, accountService);

        assertThatThrownBy(() -> consumer.onTrade(new ConsumerRecord<>("surprising.perp.match.trades.v1",
                1, 10L, "ETH-USDT", objectMapper.writeValueAsString(TRADE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process match trade")
                .satisfies(ex -> assertThat(ex.getCause())
                        .hasMessageContaining("match trade Kafka key must match payload symbol"));

        assertThat(accountService.processed).isNull();
    }

    private static final class RecordingAccountService extends AccountService {
        private MatchTradeEvent processed;

        private RecordingAccountService() {
            super(null, null);
        }

        @Override
        public void processTrade(MatchTradeEvent trade) {
            processed = trade;
        }
    }
}
