package com.surprising.aeron.exporter;

import com.surprising.aeron.client.SurprisingAeronClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

public final class InputBridgeMain {

    private InputBridgeMain() {
    }

    public static void main(String[] args) {
        var productLine = ExporterConfiguration.productLine();
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, ExporterConfiguration.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "surprising-core-input-" + productLine.topicSegment(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (var client = SurprisingAeronClient.connect(productLine, ExporterConfiguration.aeronHosts(),
                     ExporterConfiguration.aeronEgressHost(), ExporterConfiguration.aeronTimeout());
             var consumer = new KafkaConsumer<String, byte[]>(properties)) {
            consumer.subscribe(Arrays.stream(ExporterConfiguration.inputTopics().split(","))
                    .map(String::trim).filter(topic -> !topic.isEmpty()).toList());
            var worker = new KafkaInputWorker(productLine, consumer,
                    new KafkaInputBridge(productLine, client::submit));
            System.out.printf("Kafka input bridge started productLine=%s%n", productLine);
            while (!Thread.currentThread().isInterrupted()) {
                worker.pollOnce(Duration.ofMillis(250));
            }
        }
    }
}
