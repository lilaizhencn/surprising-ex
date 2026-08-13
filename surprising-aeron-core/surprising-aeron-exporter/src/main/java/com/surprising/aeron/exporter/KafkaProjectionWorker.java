package com.surprising.aeron.exporter;

import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.product.api.ProductLine;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

public final class KafkaProjectionWorker {

    private final ProductLine productLine;
    private final Consumer<String, byte[]> consumer;
    private final JdbcCoreEventProjector projector;

    public KafkaProjectionWorker(
            ProductLine productLine,
            Consumer<String, byte[]> consumer,
            JdbcCoreEventProjector projector) {
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    public int pollOnce(Duration timeout) throws SQLException {
        int processed = 0;
        for (ConsumerRecord<String, byte[]> record : consumer.poll(timeout)) {
            projector.project(productLine, CoreMessageCodec.decode(record.value()));
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            consumer.commitSync(Collections.singletonMap(partition,
                    new OffsetAndMetadata(Math.incrementExact(record.offset()))));
            processed++;
        }
        return processed;
    }
}
