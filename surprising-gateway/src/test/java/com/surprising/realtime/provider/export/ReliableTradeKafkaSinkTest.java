package com.surprising.realtime.provider.export;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Test;

class ReliableTradeKafkaSinkTest {
    @Test void closesProducerWhenInitializationFailsBeforeResourceIsRegistered() {
        @SuppressWarnings("unchecked") Producer<String, String> producer = mock(Producer.class);
        doThrow(new IllegalStateException("Kafka unavailable")).when(producer).initTransactions();
        assertThatThrownBy(() -> new ReliableTradeKafkaSink(producer, ProductLine.LINEAR_PERPETUAL, 0))
                .isInstanceOf(IllegalStateException.class).hasMessage("Kafka unavailable");
        verify(producer).close(Duration.ofSeconds(10));
    }
    @Test void preservesTheOriginalCachedFailureWhenProducerCleanupRethrowsIt() {
        @SuppressWarnings("unchecked") Producer<String, String> producer = mock(Producer.class);
        var unavailable = new IllegalStateException("Kafka unavailable");
        doThrow(unavailable).when(producer).initTransactions();
        doThrow(unavailable).when(producer).close(Duration.ofSeconds(10));
        assertThatThrownBy(() -> new ReliableTradeKafkaSink(producer, ProductLine.LINEAR_PERPETUAL, 0))
                .isSameAs(unavailable);
    }

    @Test void abortRethrowingTheCommitFailureCannotMaskTheOriginalError() {
        @SuppressWarnings("unchecked") Producer<String, String> producer = mock(Producer.class);
        var fenced = new org.apache.kafka.common.errors.ProducerFencedException("producer fenced");
        doThrow(fenced).when(producer).commitTransaction();
        doThrow(fenced).when(producer).abortTransaction();
        var payload = java.nio.ByteBuffer.allocate(25).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putLong(100).putLong(2).putLong(1).put((byte) 0).array();
        var frame = new com.surprising.aeron.protocol.RealtimeFrame(ProductLine.LINEAR_PERPETUAL,
                com.surprising.aeron.protocol.RealtimeFrame.Kind.TRADE, 0, 1, 0, 1, 0, "1", "trade-1", payload);
        try (var sink = new ReliableTradeKafkaSink(producer, ProductLine.LINEAR_PERPETUAL, 0)) {
            assertThatThrownBy(() -> sink.publish(java.util.List.of(frame))).isSameAs(fenced);
            verify(producer).abortTransaction();
        }
    }
}
