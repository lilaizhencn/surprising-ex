package com.surprising.trading.trigger.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.model.TriggerOutboxRecord;
import com.surprising.trading.trigger.repository.TriggerOrderOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class TriggerOrderOutboxPublisherTest {

    @Mock
    private TriggerOrderOutboxRepository repository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void publishesCommittedStatusAndMarksItComplete() {
        TriggerOrderOutboxPublisher publisher = new TriggerOrderOutboxPublisher(properties(), repository,
                kafkaTemplate);
        TriggerOutboxRecord row = new TriggerOutboxRecord(11L, "trigger-topic", "BTC-USDT", "payload");
        when(repository.claimPending(anyInt(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(row));
        when(kafkaTemplate.send("trigger-topic", "BTC-USDT", "payload"))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();

        verify(repository).markPublished(eq(11L), any(Instant.class));
        verify(repository, never()).markFailed(eq(11L), any(), any(Instant.class));
    }

    @Test
    void failedPublishRemainsRetryable() {
        TriggerOrderOutboxPublisher publisher = new TriggerOrderOutboxPublisher(properties(), repository,
                kafkaTemplate);
        TriggerOutboxRecord row = new TriggerOutboxRecord(12L, "trigger-topic", "BTC-USDT", "payload");
        CompletableFuture<SendResult<String, String>> failure = new CompletableFuture<>();
        failure.completeExceptionally(new IllegalStateException("kafka unavailable"));
        when(repository.claimPending(anyInt(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(row));
        when(kafkaTemplate.send("trigger-topic", "BTC-USDT", "payload")).thenReturn(failure);

        publisher.publishPending();

        verify(repository).markFailed(eq(12L), any(), any(Instant.class));
        verify(repository, never()).markPublished(eq(12L), any(Instant.class));
    }

    @Test
    void cleanupDrainsBoundedPublishedBatches() {
        TriggerProperties properties = properties();
        properties.getOutbox().setCleanupBatchSize(2);
        properties.getOutbox().setCleanupMaxBatches(3);
        TriggerOrderOutboxPublisher publisher = new TriggerOrderOutboxPublisher(
                properties, repository, kafkaTemplate);
        when(repository.deletePublishedBefore(any(Instant.class), eq(2)))
                .thenReturn(2, 1);

        publisher.cleanupPublished();

        verify(repository, times(2)).deletePublishedBefore(any(Instant.class), eq(2));
    }

    private TriggerProperties properties() {
        TriggerProperties properties = new TriggerProperties();
        properties.getOutbox().setBatchSize(1);
        properties.getOutbox().setSendTimeout(Duration.ofMillis(100));
        return properties;
    }
}
