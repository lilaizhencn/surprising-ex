package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.api.model.TradeEvent;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PublicTradeEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecentTradeQueryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CandlestickProperties properties = new CandlestickProperties();
    @SuppressWarnings("unchecked")
    private final Consumer<String, String> consumer = mock(Consumer.class);
    private final PublicTradeEventMapper tradeMapper = mock(PublicTradeEventMapper.class);
    private final AtomicInteger created = new AtomicInteger();
    private final RecentTradeQueryService service = new RecentTradeQueryService(properties, tradeMapper, mapper,
            () -> { created.incrementAndGet(); return consumer; });
    private final TopicPartition partition = new TopicPartition(properties.getKafka().getTradeTopic(), 0);

    private void prepare() {
        when(consumer.partitionsFor(eq(partition.topic()), any(Duration.class)))
                .thenReturn(List.of(new PartitionInfo(partition.topic(), 0, null, null, null)));
        when(consumer.endOffsets(eq(List.of(partition)), any(Duration.class))).thenReturn(Map.of(partition, 3L));
        when(consumer.beginningOffsets(eq(List.of(partition)), any(Duration.class))).thenReturn(Map.of(partition, 0L));
        var position = new java.util.concurrent.atomic.AtomicLong();
        doAnswer(call -> { position.set(call.getArgument(1)); return null; }).when(consumer).seek(eq(partition), anyLong());
        when(consumer.position(eq(partition), any(Duration.class))).thenAnswer(call -> position.get());
        when(consumer.poll(any(Duration.class))).thenAnswer(call -> {
            position.set(4);
            // The fourth event was committed after the query's captured end boundary.
            return new ConsumerRecords<>(Map.of(partition, List.of(record(0, "1", 1), record(1, "2", 2),
                    record(2, "1", 3), record(3, "1", 4))), Map.of());
        });
        when(tradeMapper.toTradeEvent(any())).thenAnswer(call -> {
            PublicTradeEvent event = call.getArgument(0);
            return new TradeEvent(event.instrumentId(), event.tradeId(), event.sequence(), event.eventTime(),
                    BigDecimal.valueOf(80000), new BigDecimal("0.06"),
                    com.surprising.candlestick.api.model.TradeSide.BUY, null, null);
        });
    }

    private ConsumerRecord<String, String> record(long offset, String instrument, long sequence) {
        return new ConsumerRecord<>(partition.topic(), 0, offset, instrument,
                mapper.writeValueAsString(new PublicTradeEvent("trade-" + sequence, sequence, instrument,
                        OrderSide.BUY, 80000L, 6L, Instant.parse("2026-10-09T00:00:00Z"), "trace-" + sequence)));
    }

    @Test void reusesOneConsumerAndKeepsCommittedBoundaryInstrumentQuantityAndDescendingOrder() {
        prepare();
        var first = service.recent("1", 50);
        var second = service.recent("1", 1);
        assertThat(created.get()).isOne();
        assertThat(first).extracting(RecentTradeQueryService.RecentTrade::sequence).containsExactly(3L, 1L);
        assertThat(second).hasSize(1);
        assertThat(first.getFirst().quantity()).isEqualByComparingTo("0.06");
        assertThat(first.getFirst().quantitySteps()).isEqualTo(6);
        assertThat(first.getFirst().eventTime()).isEqualTo(Instant.parse("2026-10-09T00:00:00Z"));
        verify(consumer, never()).commitSync();
        verify(consumer, never()).subscribe(anyList());
        service.close();
        verify(consumer).close(Duration.ofSeconds(2));
        assertThatThrownBy(() -> service.recent("1", 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test void concurrentRequestsCannotSeekOrPollTheSameConsumerAtOnce() throws Exception {
        prepare();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var polls = new AtomicInteger();
        doAnswer(call -> {
            if (polls.incrementAndGet() == 1) {
                entered.countDown();
                assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
            }
            return List.of(new PartitionInfo(partition.topic(), 0, null, null, null));
        }).when(consumer).partitionsFor(anyString(), any(Duration.class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.recent("1", 1));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.recent("1", 1));
            assertThat(polls.get()).isOne();
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).hasSize(1);
            assertThat(second.get(2, TimeUnit.SECONDS)).hasSize(1);
            assertThat(created.get()).isOne();
        } finally { release.countDown(); service.close(); }
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void everyProductUsesItsOwnTopicAndReadCommittedConsumerWithoutRequestGroups(ProductLine product) {
        properties.getKafka().setProductLine(product);
        var config = RecentTradeQueryService.consumerConfig(properties);
        assertThat(config.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG)).isEqualTo("read_committed");
        assertThat(config).doesNotContainKey(ConsumerConfig.GROUP_ID_CONFIG);
        assertThat(config.get(ConsumerConfig.CLIENT_ID_CONFIG)).isEqualTo("recent-trades-query-" + product);
        assertThat(properties.getKafka().getTradeTopic()).isEqualTo(com.surprising.product.api.ProductTopicNames.of(product).matchTradesTopic());
    }

    @Test void invalidInputsDoNotCreateKafkaClients() {
        assertThatThrownBy(() -> service.recent("bad\nkey", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recent("1", 51)).isInstanceOf(IllegalArgumentException.class);
        assertThat(created.get()).isZero();
    }
}
