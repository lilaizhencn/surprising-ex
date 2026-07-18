package com.surprising.account.provider.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.AccountOutboxRecord;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class AccountOutboxPublisherTest {

    @Mock
    private AccountOutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private AccountOutboxPublisher publisher;

    @AfterEach
    void tearDown() {
        if (publisher != null) {
            publisher.shutdown();
        }
    }

    @Test
    void asyncPublisherSubmitsSameKeyWindowBeforeWaitingAndBatchMarksSuccesses() throws Exception {
        AccountProperties properties = properties();
        publisher = new AccountOutboxPublisher(properties, outboxRepository, kafkaTemplate);
        List<AccountOutboxRecord> rows = List.of(
                record(1L, "key-a", "payload-1"),
                record(2L, "key-a", "payload-2"));
        CompletableFuture<SendResult<String, String>> first = new CompletableFuture<>();
        CompletableFuture<SendResult<String, String>> second = new CompletableFuture<>();
        when(outboxRepository.claimPending(anyInt(), any(Instant.class), any(Instant.class)))
                .thenReturn(rows)
                .thenReturn(List.of());
        when(kafkaTemplate.send(eq("topic"), eq("key-a"), eq("payload-1")))
                .thenReturn(first);
        when(kafkaTemplate.send(eq("topic"), eq("key-a"), eq("payload-2")))
                .thenReturn(second);

        CompletableFuture<Void> publishing = CompletableFuture.runAsync(publisher::publishPending);

        InOrder inOrder = inOrder(kafkaTemplate);
        inOrder.verify(kafkaTemplate, timeout(1_000)).send("topic", "key-a", "payload-1");
        inOrder.verify(kafkaTemplate, timeout(1_000)).send("topic", "key-a", "payload-2");
        first.complete(null);
        second.complete(null);
        publishing.get(2, TimeUnit.SECONDS);

        verify(outboxRepository).markPublished(eq(List.of(1L, 2L)), any(Instant.class));
        verify(outboxRepository, never()).markFailed(anyLong(), any(), any(Instant.class));
    }

    @Test
    void asyncPublisherOnlyMarksContinuousSuccessPrefixWhenWindowFails() {
        publisher = new AccountOutboxPublisher(properties(), outboxRepository, kafkaTemplate);
        List<AccountOutboxRecord> rows = List.of(
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
        when(kafkaTemplate.send(eq("topic"), eq("key-a"), eq("payload-3")))
                .thenReturn(success());

        publisher.publishPending();

        verify(kafkaTemplate).send("topic", "key-a", "payload-3");
        verify(outboxRepository).markPublished(eq(List.of(1L)), any(Instant.class));
        verify(outboxRepository).markFailed(eq(2L), any(), any(Instant.class));
    }

    @Test
    void userCommandTopicWaitsForEachAckToPreserveStrictCommandOrder() throws Exception {
        AccountProperties properties = properties();
        publisher = new AccountOutboxPublisher(properties, outboxRepository, kafkaTemplate);
        String topic = properties.getKafka().getUserCommandsTopic();
        List<AccountOutboxRecord> rows = List.of(
                record(1L, topic, "LINEAR_PERPETUAL:1001", "payload-1"),
                record(2L, topic, "LINEAR_PERPETUAL:1001", "payload-2"));
        CompletableFuture<SendResult<String, String>> first = new CompletableFuture<>();
        when(outboxRepository.claimPending(anyInt(), any(Instant.class), any(Instant.class)))
                .thenReturn(rows)
                .thenReturn(List.of());
        when(kafkaTemplate.send(topic, "LINEAR_PERPETUAL:1001", "payload-1"))
                .thenReturn(first);
        when(kafkaTemplate.send(topic, "LINEAR_PERPETUAL:1001", "payload-2"))
                .thenReturn(success());

        CompletableFuture<Void> publishing = CompletableFuture.runAsync(publisher::publishPending);

        verify(kafkaTemplate, timeout(1_000))
                .send(topic, "LINEAR_PERPETUAL:1001", "payload-1");
        verify(kafkaTemplate, never())
                .send(topic, "LINEAR_PERPETUAL:1001", "payload-2");
        first.complete(null);
        verify(kafkaTemplate, timeout(1_000))
                .send(topic, "LINEAR_PERPETUAL:1001", "payload-2");
        publishing.get(2, TimeUnit.SECONDS);

        verify(outboxRepository).markPublished(eq(List.of(1L, 2L)), any(Instant.class));
    }

    private AccountProperties properties() {
        AccountProperties properties = new AccountProperties();
        properties.getOutbox().setBatchSize(10);
        properties.getOutbox().setAsyncEnabled(true);
        properties.getOutbox().setMaxInFlight(2);
        properties.getOutbox().setSendWindowSize(5);
        return properties;
    }

    private AccountOutboxRecord record(long id, String key, String payload) {
        return record(id, "topic", key, payload);
    }

    private AccountOutboxRecord record(long id, String topic, String key, String payload) {
        return new AccountOutboxRecord(id, topic, key, payload);
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
