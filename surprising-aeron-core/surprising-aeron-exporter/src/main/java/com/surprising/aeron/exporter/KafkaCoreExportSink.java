package com.surprising.aeron.exporter;

import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

public final class KafkaCoreExportSink implements CoreExportSink, AutoCloseable {

    private final KafkaProducer<String, byte[]> producer;

    public KafkaCoreExportSink(Map<String, Object> properties) {
        Objects.requireNonNull(properties, "properties");
        var configuration = new java.util.HashMap<>(properties);
        configuration.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configuration.put(ProducerConfig.ACKS_CONFIG, "all");
        configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producer = new KafkaProducer<>(configuration);
    }

    @Override
    public void publish(ProductLine productLine, List<CoreMessage> events) throws Exception {
        String topic = topic(productLine);
        List<Future<RecordMetadata>> writes = new ArrayList<>(events.size());
        for (CoreMessage message : events) {
            long sequence = CoreExportCodec.decodeEvent(message.payload()).exportSequence();
            writes.add(producer.send(new ProducerRecord<>(topic,
                    productLine.name() + ":" + sequence, CoreMessageCodec.encode(message))));
        }
        for (Future<RecordMetadata> write : writes) {
            write.get();
        }
    }

    public static String topic(ProductLine productLine) {
        return "surprising." + productLine.topicSegment() + ".core.events.v1";
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(10));
    }
}
