package com.surprising.aeron.exporter;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

public final class ExporterInfrastructureSmokeMain {

    private ExporterInfrastructureSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        var productLine = ExporterConfiguration.productLine();
        long sequence = Long.parseLong(System.getenv().getOrDefault(
                "EXPORT_SMOKE_SEQUENCE", Long.toString(System.currentTimeMillis())));
        UUID commandId = UUID.nameUUIDFromBytes((productLine + ":infra-smoke:" + sequence).getBytes());
        CoreExportEvent event = new CoreExportEvent(sequence, sequence, sequence, commandId,
                CoreMessageType.PROBE_INCREMENT, ResponseStatus.APPLIED, CoreResultCode.NONE, 0,
                CoreProtocol.probePayload(1));
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                commandId, productLine, CommandSource.OPERATIONS, 1, sequence, 0, sequence, sequence)
                .exportEvent(sequence), CoreExportCodec.encodeEvent(event));
        try (var sink = new KafkaCoreExportSink(ExporterConfiguration.kafkaProducerProperties())) {
            sink.publish(productLine, List.of(message));
        }

        Map<String, Object> consumerProperties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, ExporterConfiguration.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "surprising-infra-smoke-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        var dataSource = new DriverManagerDataSource(ProjectionConfiguration.databaseUrl(),
                ProjectionConfiguration.databaseUser(), ProjectionConfiguration.databasePassword());
        var projector = new JdbcCoreEventProjector(dataSource);
        boolean consumed = false;
        try (var consumer = new KafkaConsumer<String, byte[]>(consumerProperties)) {
            consumer.subscribe(List.of(KafkaCoreExportSink.topic(productLine)));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!consumed && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if ((productLine.name() + ":" + sequence).equals(record.key())) {
                        CoreMessage published = com.surprising.aeron.protocol.CoreMessageCodec.decode(record.value());
                        if (!projector.project(productLine, published) || projector.project(productLine, published)) {
                            throw new IllegalStateException("PostgreSQL projection is not idempotent");
                        }
                        consumed = true;
                    }
                }
            }
        }
        if (!consumed) {
            throw new IllegalStateException("timed out consuming infrastructure smoke event");
        }
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM core_event_projection WHERE product_line = ? AND export_sequence = ?")) {
            statement.setString(1, productLine.name());
            statement.setLong(2, sequence);
            try (var result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) != 1) {
                    throw new IllegalStateException("unexpected projection row count");
                }
            }
        }
        System.out.printf("exportInfrastructure=PASS productLine=%s sequence=%d kafkaTopic=%s pgRows=1%n",
                productLine, sequence, KafkaCoreExportSink.topic(productLine));
    }
}
