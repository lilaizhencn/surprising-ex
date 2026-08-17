package com.surprising.aeron.exporter;

import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class ExporterConfiguration {

    private ExporterConfiguration() {
    }

    static ProductLine productLine() {
        return ProductLine.requireExternalCode(required("PRODUCT_LINE"));
    }

    static List<String> aeronHosts() {
        return Arrays.stream(value("AERON_HOSTNAMES", "localhost,localhost,localhost").split(","))
                .map(String::trim).filter(host -> !host.isEmpty()).toList();
    }

    static String aeronEgressHost() {
        return value("AERON_EGRESS_HOSTNAME", "localhost");
    }

    static Duration aeronTimeout() {
        return Duration.ofMillis(positiveLong("AERON_RESPONSE_TIMEOUT_MS", 5_000));
    }

    static int batchSize() {
        return Math.toIntExact(positiveLong("EXPORT_BATCH_SIZE", 1_024));
    }

    static long idleMillis() {
        long configured = positiveLong("EXPORT_IDLE_MS", AdaptiveExportLoop.MIN_IDLE_MILLIS);
        return Math.max(AdaptiveExportLoop.MIN_IDLE_MILLIS,
                Math.min(configured, AdaptiveExportLoop.MAX_IDLE_MILLIS));
    }

    static Map<String, Object> kafkaProducerProperties() {
        return Map.of("bootstrap.servers", value("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
                "client.id", "surprising-core-exporter-" + productLine().topicSegment(),
                "delivery.timeout.ms", Long.toString(positiveLong("KAFKA_DELIVERY_TIMEOUT_MS", 10_000)),
                "request.timeout.ms", Long.toString(positiveLong("KAFKA_REQUEST_TIMEOUT_MS", 3_000)),
                "max.block.ms", Long.toString(positiveLong("KAFKA_MAX_BLOCK_MS", 5_000)));
    }

    static String kafkaBootstrapServers() {
        return value("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
    }

    static String databaseUrl() {
        return value("DATABASE_URL", "jdbc:postgresql://localhost:5432/postgres");
    }

    static String databaseUser() {
        return value("DATABASE_USER", "postgres");
    }

    static String databasePassword() {
        return value("DATABASE_PASSWORD", "postgres");
    }

    static String inputTopics() {
        return required("CORE_INPUT_TOPICS");
    }

    private static String required(String name) {
        String configured = System.getenv(name);
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("missing required environment variable " + name);
        }
        return configured.trim();
    }

    private static String value(String name, String fallback) {
        String configured = System.getenv(name);
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }

    private static long positiveLong(String name, long fallback) {
        long value = Long.parseLong(ExporterConfiguration.value(name, Long.toString(fallback)));
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
