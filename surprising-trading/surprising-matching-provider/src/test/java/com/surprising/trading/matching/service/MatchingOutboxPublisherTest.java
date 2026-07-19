package com.surprising.trading.matching.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.StoredOutboxRecord;
import com.surprising.trading.matching.repository.MatchingOutboxRepository;
import com.surprising.trading.matching.repository.MatchingOutboxRepository.MatchingOutboxStreamBatch;
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
class MatchingOutboxPublisherTest {

    @Mock
    private MatchingOutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private MatchingOutboxPublisher publisher;

    @AfterEach
    void tearDown() {
        if (publisher != null) {
            publisher.shutdown();
        }
    }

    @Test
    void asyncPublisherKeepsSameKeyOrderAndBatchMarksSuccesses() {
        publisher = new MatchingOutboxPublisher(properties(), outboxRepository, kafkaTemplate);
        List<StoredOutboxRecord> rows = List.of(
                record(1L, "key-a", "payload-1"),
                record(2L, "key-a", "payload-2"));
        when(outboxRepository.claimPendingBatches(anyInt(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(batch(rows)))
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
    void asyncPublisherPipelinesSameKeyBatchAndRetriesFromFirstFailedRow() {
        publisher = new MatchingOutboxPublisher(properties(), outboxRepository, kafkaTemplate);
        List<StoredOutboxRecord> rows = List.of(
                record(1L, "key-a", "payload-1"),
                record(2L, "key-a", "payload-2"),
                record(3L, "key-a", "payload-3"));
        when(outboxRepository.claimPendingBatches(anyInt(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(batch(rows)))
                .thenReturn(List.of());
        when(kafkaTemplate.send(eq("topic"), eq("key-a"), eq("payload-1")))
                .thenReturn(success());
        when(kafkaTemplate.send(eq("topic"), eq("key-a"), eq("payload-2")))
                .thenReturn(failure());
        when(kafkaTemplate.send(eq("topic"), eq("key-a"), eq("payload-3")))
                .thenReturn(success());

        publisher.publishPending();

        verify(kafkaTemplate).send("topic", "key-a", "payload-3");
        verify(outboxRepository).markPublished(eq(List.of(1L)), any(Instant.class));
        verify(outboxRepository).markFailed(eq(2L), any(), any(Instant.class));
        verify(outboxRepository).releasePending(eq(List.of(3L)), any(Instant.class));
    }

    @Test
    void cleanupDrainsBoundedPublishedBatches() {
        MatchingProperties properties = properties();
        properties.getOutbox().setCleanupBatchSize(2);
        properties.getOutbox().setCleanupMaxBatches(3);
        publisher = new MatchingOutboxPublisher(properties, outboxRepository, kafkaTemplate);
        when(outboxRepository.deletePublishedBefore(any(Instant.class), eq(2)))
                .thenReturn(2, 1);

        publisher.cleanupPublished();

        verify(outboxRepository, times(2)).deletePublishedBefore(any(Instant.class), eq(2));
    }

    private MatchingProperties properties() {
        MatchingProperties properties = new MatchingProperties();
        properties.getOutbox().setBatchSize(10);
        properties.getOutbox().setAsyncEnabled(true);
        properties.getOutbox().setMaxInFlight(2);
        return properties;
    }

    private StoredOutboxRecord record(long id, String key, String payload) {
        return new StoredOutboxRecord(id, "topic", key, payload, Instant.parse("2026-07-01T00:00:00Z"));
    }

    private MatchingOutboxStreamBatch batch(List<StoredOutboxRecord> rows) {
        return new MatchingOutboxStreamBatch("topic", "key-a", rows);
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
