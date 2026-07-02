package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderSide;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MatchTradeConsumer consumer = new MatchTradeConsumer(objectMapper, accountService,
                new AccountSettlementMetrics(meterRegistry));

        consumer.onTrade(new ConsumerRecord<>("surprising.perp.match.trades.v1", 1, 10L,
                "BTC-USDT", objectMapper.writeValueAsString(TRADE)));

        assertThat(accountService.processed).isEqualTo(TRADE);
        assertThat(meterRegistry.get("surprising.account.match_trade.events")
                .tag("outcome", "processed")
                .counter()
                .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("surprising.account.match_trade.processing")
                .tag("outcome", "processed")
                .timer()
                .count()).isEqualTo(1L);
        assertThat(meterRegistry.get("surprising.account.match_trade.event_lag")
                .tag("outcome", "processed")
                .timer()
                .count()).isEqualTo(1L);
    }

    @Test
    void processesBatchRecordsInPollOrder() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingAccountService accountService = new RecordingAccountService();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MatchTradeConsumer consumer = new MatchTradeConsumer(objectMapper, accountService,
                new AccountSettlementMetrics(meterRegistry));
        MatchTradeEvent second = new MatchTradeEvent(
                9202L,
                9102L,
                "BTC-USDT",
                9004L,
                1L,
                2004L,
                OrderSide.SELL,
                9003L,
                1L,
                1003L,
                600_001L,
                4L,
                true,
                true,
                Instant.parse("2026-07-01T00:00:01Z"));

        consumer.onTrades(List.of(
                new ConsumerRecord<>("surprising.perp.match.trades.v1", 1, 10L,
                        "BTC-USDT", objectMapper.writeValueAsString(TRADE)),
                new ConsumerRecord<>("surprising.perp.match.trades.v1", 1, 11L,
                        "BTC-USDT", objectMapper.writeValueAsString(second))));

        assertThat(accountService.processedEvents)
                .extracting(MatchTradeEvent::tradeId)
                .containsExactly(9201L, 9202L);
        assertThat(meterRegistry.get("surprising.account.match_trade.events")
                .tag("outcome", "processed")
                .counter()
                .count()).isEqualTo(2.0d);
    }

    @Test
    void rejectsTradeWhenKafkaKeyDoesNotMatchPayloadSymbol() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingAccountService accountService = new RecordingAccountService();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MatchTradeConsumer consumer = new MatchTradeConsumer(objectMapper, accountService,
                new AccountSettlementMetrics(meterRegistry));

        assertThatThrownBy(() -> consumer.onTrade(new ConsumerRecord<>("surprising.perp.match.trades.v1",
                1, 10L, "ETH-USDT", objectMapper.writeValueAsString(TRADE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process match trade")
                .satisfies(ex -> assertThat(ex.getCause())
                        .hasMessageContaining("match trade Kafka key must match payload symbol"));

        assertThat(accountService.processed).isNull();
        assertThat(meterRegistry.get("surprising.account.match_trade.events")
                .tag("outcome", "failed")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void recordsDuplicateTradeMetricWhenAccountServiceSkipsIdempotentReplay() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingAccountService accountService = new RecordingAccountService();
        accountService.processedResult = false;
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MatchTradeConsumer consumer = new MatchTradeConsumer(objectMapper, accountService,
                new AccountSettlementMetrics(meterRegistry));

        consumer.onTrade(new ConsumerRecord<>("surprising.perp.match.trades.v1", 1, 10L,
                "BTC-USDT", objectMapper.writeValueAsString(TRADE)));

        assertThat(meterRegistry.get("surprising.account.match_trade.events")
                .tag("outcome", "duplicate")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    private static final class RecordingAccountService extends AccountService {
        private MatchTradeEvent processed;
        private final List<MatchTradeEvent> processedEvents = new ArrayList<>();
        private boolean processedResult = true;

        private RecordingAccountService() {
            super(null, null);
        }

        @Override
        public boolean processTradeIfNew(MatchTradeEvent trade) {
            processed = trade;
            processedEvents.add(trade);
            return processedResult;
        }
    }
}
