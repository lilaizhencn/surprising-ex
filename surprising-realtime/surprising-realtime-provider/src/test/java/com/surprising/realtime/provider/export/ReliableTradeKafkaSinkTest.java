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
}
