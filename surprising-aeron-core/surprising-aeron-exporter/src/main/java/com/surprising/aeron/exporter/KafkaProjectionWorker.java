package com.surprising.aeron.exporter;

import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.product.api.ProductLine;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
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
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        for (ConsumerRecord<String, byte[]> record : consumer.poll(timeout)) {
            projector.project(productLine, CoreMessageCodec.decode(record.value()));
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            offsets.merge(partition, new OffsetAndMetadata(Math.incrementExact(record.offset())),
                    (current, candidate) -> current.offset() >= candidate.offset() ? current : candidate);
            processed++;
        }
        if (!offsets.isEmpty()) {
            consumer.commitSync(offsets);
        }
        return processed;
    }
}
