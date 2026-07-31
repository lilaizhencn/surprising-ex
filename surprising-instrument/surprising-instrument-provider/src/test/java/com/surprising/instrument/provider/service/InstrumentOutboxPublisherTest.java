package com.surprising.instrument.provider.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.provider.config.InstrumentProperties;
import com.surprising.instrument.provider.model.InstrumentOutboxRecord;
import com.surprising.instrument.provider.repository.InstrumentOutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class InstrumentOutboxPublisherTest {

    @Test
    void marksEventPublishedOnlyAfterKafkaAcknowledgement() {
        InstrumentProperties properties = new InstrumentProperties();
        InstrumentOutboxRepository repository = mock(InstrumentOutboxRepository.class);
        KafkaTemplate<String, String> kafkaTemplate = kafkaTemplate();
        InstrumentOutboxRecord row = new InstrumentOutboxRecord(
                11L, "instrument.events", "BTC-USDT", "STATUS_CHANGED", "{}", Instant.now());
        when(repository.claimPending(eq(100), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(row));
        when(kafkaTemplate.send("instrument.events", "BTC-USDT", "{}"))
                .thenReturn(CompletableFuture.completedFuture(null));

        new InstrumentOutboxPublisher(properties, repository, kafkaTemplate).publishPending();

        verify(repository).markPublished(eq(11L), any(Instant.class));
        verify(repository, never()).markFailed(eq(11L), any(), any(Instant.class));
    }

    @Test
    void retainsFailedEventForRetry() {
        InstrumentProperties properties = new InstrumentProperties();
        InstrumentOutboxRepository repository = mock(InstrumentOutboxRepository.class);
        KafkaTemplate<String, String> kafkaTemplate = kafkaTemplate();
        InstrumentOutboxRecord row = new InstrumentOutboxRecord(
                12L, "instrument.events", "BTC-USDT", "STATUS_CHANGED", "{}", Instant.now());
        when(repository.claimPending(eq(100), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(row));
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> failed =
                new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send("instrument.events", "BTC-USDT", "{}")).thenReturn(failed);

        new InstrumentOutboxPublisher(properties, repository, kafkaTemplate).publishPending();

        verify(repository, never()).markPublished(eq(12L), any(Instant.class));
        verify(repository).markFailed(eq(12L), any(), any(Instant.class));
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }
}
