package com.surprising.aeron.exporter;

import com.surprising.aeron.protocol.CoreInputEventCodec;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

public final class KafkaInputWorker {

    private final ProductLine productLine;
    private final Consumer<String, byte[]> consumer;
    private final KafkaInputBridge bridge;

    public KafkaInputWorker(
            ProductLine productLine,
            Consumer<String, byte[]> consumer,
            KafkaInputBridge bridge) {
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    public int pollOnce(Duration timeout) {
        int processed = 0;
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        for (ConsumerRecord<String, byte[]> record : consumer.poll(timeout)) {
            var event = CoreInputEventCodec.decode(record.value());
            if (event.productLine() != productLine) {
                throw new IllegalArgumentException("Kafka input product line mismatch");
            }
            var response = bridge.submit(new KafkaInputBridge.KafkaInput(record.topic(), record.partition(),
                    record.offset(), Math.max(0, record.timestamp())), event.commandType(), event.userId(),
                    event.commandPayload());
            if (!KafkaInputBridge.mayCommitOffset(response)) {
                throw new IllegalStateException("core result does not authorize Kafka offset commit");
            }
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
