package com.surprising.aeron.exporter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

public final class ProjectionMain {

    private ProjectionMain() {
    }

    public static void main(String[] args) throws Exception {
        var productLine = ExporterConfiguration.productLine();
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, ExporterConfiguration.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "surprising-core-projection-" + productLine.topicSegment(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (var consumer = new KafkaConsumer<String, byte[]>(properties)) {
            consumer.subscribe(List.of(KafkaCoreExportSink.topic(productLine)));
            var projector = new JdbcCoreEventProjector(new DriverManagerDataSource(
                    ExporterConfiguration.databaseUrl(), ExporterConfiguration.databaseUser(),
                    ExporterConfiguration.databasePassword()));
            var worker = new KafkaProjectionWorker(productLine, consumer, projector);
            System.out.printf("Core projection started productLine=%s%n", productLine);
            while (!Thread.currentThread().isInterrupted()) {
                worker.pollOnce(Duration.ofMillis(250));
            }
        }
    }
}
