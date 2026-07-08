package com.surprising.trading.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OutboxRecord;
import com.surprising.trading.order.repository.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher publisher;

    @AfterEach
    void tearDown() {
        if (publisher != null) {
            publisher.shutdown();
        }
    }

    @Test
    void asyncPublisherKeepsSameKeyOrderAndBatchMarksSuccesses() {
        publisher = new OutboxPublisher(properties(), outboxRepository, kafkaTemplate);
        List<OutboxRecord> rows = List.of(
                record(1L, "key-a", "payload-1"),
                record(2L, "key-a", "payload-2"));
        when(outboxRepository.claimPending(anyInt(), any(Instant.class), any(Instant.class)))
                .thenReturn(rows)
                .thenReturn(List.of());
        when(kafkaTemplate.send(eq("topic"), eq("key-a"), eq("payload-1")))
                .thenReturn(success());
        when(kafkaTemplate.send(eq("topic"), eq("key-a"), eq("payload-2")))
                .thenReturn(success());

        publisher.publishPending();

        InOrder inOrder = inOrder(kafkaTemplate);
        inOrder.verify(kafkaTemplate).send("topic", "key-a", "payload-1");
        inOrder.verify(kafkaTemplate).send("topic", "key-a", "payload-2");
        verify(outboxRepository).markPublished(eq(List.of(1L, 2L)), any(Instant.class));
        verify(outboxRepository, never()).markFailed(anyLong(), any(), any(Instant.class));
    }

    @Test
    void asyncPublisherStopsSameKeyGroupAfterFailure() {
        publisher = new OutboxPublisher(properties(), outboxRepository, kafkaTemplate);
        List<OutboxRecord> rows = List.of(
                record(1L, "key-a", "payload-1"),
                record(2L, "key-a", "payload-2"),
                record(3L, "key-a", "payload-3"));
        when(outboxRepository.claimPending(anyInt(), any(Instant.class), any(Instant.class)))
                .thenReturn(rows)
                .thenReturn(List.of());
        when(kafkaTemplate.send(eq("topic"), eq("key-a"), eq("payload-1")))
                .thenReturn(success());
        when(kafkaTemplate.send(eq("topic"), eq("key-a"), eq("payload-2")))
                .thenReturn(failure());

        publisher.publishPending();

        verify(kafkaTemplate, never()).send("topic", "key-a", "payload-3");
        verify(outboxRepository).markPublished(eq(List.of(1L)), any(Instant.class));
        verify(outboxRepository).markFailed(eq(2L), any(), any(Instant.class));
    }

    private TradingOrderProperties properties() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getOutbox().setBatchSize(10);
        properties.getOutbox().setAsyncEnabled(true);
        properties.getOutbox().setMaxInFlight(2);
        return properties;
    }

    private OutboxRecord record(long id, String key, String payload) {
        return new OutboxRecord(id, "topic", key, payload, Instant.parse("2026-07-01T00:00:00Z"));
    }

    private CompletableFuture<SendResult<String, String>> success() {
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<SendResult<String, String>> failure() {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException("kafka unavailable"));
        return future;
    }
}
