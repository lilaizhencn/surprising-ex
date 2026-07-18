package com.surprising.trading.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.OrderBookDepthEvent;
import com.surprising.trading.api.model.OrderBookDepthUpdateType;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.matching.config.MatchingProperties;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

class KafkaOrderBookDepthPublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void keepsOnlyTheLatestSnapshotIndependentlyForEachSymbol() throws Exception {
        MatchingProperties properties = new MatchingProperties();
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        KafkaOrderBookDepthPublisher publisher = new KafkaOrderBookDepthPublisher(
                new ObjectMapper(), properties, kafkaTemplate);

        publisher.offer(snapshot("BTC-USDT", 1L, 10L));
        publisher.offer(snapshot("BTC-USDT", 2L, 20L));
        publisher.offer(snapshot("ETH-USDT", 1L, 30L));

        assertThat(publisher.stats().symbols()).isEqualTo(2);
        assertThat(publisher.stats().readySymbols()).isEqualTo(2);
        assertThat(publisher.stats().coalesced()).isEqualTo(1);

        publisher.publishPending();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(2)).send(
                org.mockito.ArgumentMatchers.eq("surprising.perp.orderbook.depth.v1"),
                key.capture(), payload.capture());
        assertThat(key.getAllValues()).containsExactly("BTC-USDT", "ETH-USDT");
        List<OrderBookDepthEvent> events = payload.getAllValues().stream()
                .map(value -> read(value, new ObjectMapper()))
                .toList();
        assertThat(events).extracting(OrderBookDepthEvent::symbol, OrderBookDepthEvent::sequence)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("BTC-USDT", 2L),
                        org.assertj.core.groups.Tuple.tuple("ETH-USDT", 1L));
        assertThat(publisher.stats().sent()).isEqualTo(2);
        assertThat(publisher.stats().inFlight()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void replacesSnapshotsThatArriveWhileTheSameSymbolIsInFlight() throws Exception {
        MatchingProperties properties = new MatchingProperties();
        properties.getMarketData().setMaxInFlight(1);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> first = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(first)
                .thenReturn(CompletableFuture.completedFuture(null));
        KafkaOrderBookDepthPublisher publisher = new KafkaOrderBookDepthPublisher(
                new ObjectMapper(), properties, kafkaTemplate);

        publisher.offer(snapshot("BTC-USDT", 1L, 10L));
        publisher.publishPending();
        publisher.offer(snapshot("BTC-USDT", 2L, 20L));
        publisher.offer(snapshot("BTC-USDT", 3L, 30L));
        publisher.publishPending();

        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());

        first.complete(null);
        publisher.publishPending();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), payload.capture());
        List<OrderBookDepthEvent> events = payload.getAllValues().stream()
                .map(value -> read(value, new ObjectMapper()))
                .toList();
        assertThat(events).extracting(OrderBookDepthEvent::sequence).containsExactly(1L, 3L);
        assertThat(publisher.stats().coalesced()).isEqualTo(1);
    }

    private OrderBookDepthEvent snapshot(String symbol, long sequence, long quantity) {
        return new OrderBookDepthEvent(symbol, sequence, Math.max(0L, sequence - 1L),
                OrderBookDepthUpdateType.SNAPSHOT, 50,
                List.of(new OrderBookLevel(100L, quantity, 1L)), List.of(),
                Instant.parse("2026-07-01T00:00:00Z").plusSeconds(sequence));
    }

    private static OrderBookDepthEvent read(String payload, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(payload, OrderBookDepthEvent.class);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
