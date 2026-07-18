package com.surprising.trading.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PublicTradeEvent;
import com.surprising.trading.matching.config.MatchingProperties;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

class KafkaPublicTradePublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void preservesEveryQueuedTradeInPerSymbolFifoOrder() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        KafkaPublicTradePublisher publisher = new KafkaPublicTradePublisher(
                new ObjectMapper(), new MatchingProperties(), kafkaTemplate);

        publisher.offer(trade("BTC-USDT", 1L));
        publisher.offer(trade("BTC-USDT", 2L));
        publisher.offer(trade("ETH-USDT", 3L));
        publisher.publishPending();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(3)).send(
                org.mockito.ArgumentMatchers.eq("surprising.perp.match.trades.v1"),
                key.capture(), payload.capture());
        assertThat(key.getAllValues()).containsExactly("BTC-USDT", "BTC-USDT", "ETH-USDT");
        List<PublicTradeEvent> events = payload.getAllValues().stream().map(this::read).toList();
        assertThat(events).extracting(PublicTradeEvent::sequence).containsExactly(1L, 2L, 3L);
        assertThat(publisher.stats().offered()).isEqualTo(3L);
        assertThat(publisher.stats().dropped()).isZero();
        assertThat(publisher.stats().sent()).isEqualTo(3L);
        assertThat(publisher.stats().queued()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dropsOnlyTheOldestTradeAfterOneSymbolExceedsItsBoundedFifo() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        KafkaPublicTradePublisher publisher = new KafkaPublicTradePublisher(
                new ObjectMapper(), new MatchingProperties(), kafkaTemplate);

        for (long sequence = 1L; sequence <= KafkaPublicTradePublisher.MAX_QUEUED_PER_SYMBOL + 1L; sequence++) {
            publisher.offer(trade("BTC-USDT", sequence));
        }

        assertThat(publisher.stats().dropped()).isEqualTo(1L);
        assertThat(publisher.stats().queued()).isEqualTo(KafkaPublicTradePublisher.MAX_QUEUED_PER_SYMBOL);

        publisher.publishPending();

        ArgumentCaptor<String> firstPayload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(KafkaPublicTradePublisher.BATCH_SIZE))
                .send(anyString(), anyString(), firstPayload.capture());
        assertThat(read(firstPayload.getAllValues().get(0)).sequence()).isEqualTo(2L);
    }

    private PublicTradeEvent trade(String symbol, long sequence) {
        return new PublicTradeEvent("trade:" + sequence, sequence, symbol, 7L, OrderSide.BUY,
                600_000L, 3L, Instant.parse("2026-07-01T00:00:00Z").plusMillis(sequence),
                "trace:" + sequence);
    }

    private PublicTradeEvent read(String payload) {
        try {
            return new ObjectMapper().readValue(payload, PublicTradeEvent.class);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
