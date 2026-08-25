package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class KafkaProjectionWorkerTest {

    private static final String TOPIC = "core.export.spot.v1";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    @Test
    void projectorFailureCommitsNoOffset() throws Exception {
        JdbcDataSource dataSource = dataSource("worker_failure");
        TrackingConsumer consumer = consumer(dataSource);
        CoreUserStateView duplicate = user(17);
        consumer.addRecord(record(41, event(1, List.of(duplicate, duplicate))));
        var worker = new KafkaProjectionWorker(ProductLine.SPOT, consumer,
                new JdbcCoreEventProjector(dataSource));

        assertThatThrownBy(() -> worker.pollOnce(Duration.ZERO)).isInstanceOf(SQLException.class);
        assertThat(consumer.commitCalls).isZero();
        assertThat(consumer.committed(java.util.Set.of(PARTITION)).get(PARTITION)).isNull();
        assertProjectionState(dataSource, 0, 0, 0);
    }

    @Test
    void successCommitsOffsetOnlyAfterTransactionReturns() throws Exception {
        JdbcDataSource dataSource = dataSource("worker_success");
        TrackingConsumer consumer = consumer(dataSource);
        consumer.addRecord(record(41, event(1, List.of(user(18)))));
        var worker = new KafkaProjectionWorker(ProductLine.SPOT, consumer,
                new JdbcCoreEventProjector(dataSource));

        assertThat(worker.pollOnce(Duration.ZERO)).isEqualTo(1);
        assertThat(consumer.commitCalls).isEqualTo(1);
        assertThat(consumer.projectionVisibleAtCommit).isTrue();
        assertThat(consumer.committed(java.util.Set.of(PARTITION)).get(PARTITION).offset()).isEqualTo(42);
        assertProjectionState(dataSource, 1, 1, 1);
    }

    private static TrackingConsumer consumer(JdbcDataSource dataSource) {
        TrackingConsumer consumer = new TrackingConsumer(dataSource);
        consumer.assign(List.of(PARTITION));
        consumer.updateBeginningOffsets(Map.of(PARTITION, 0L));
        return consumer;
    }

    private static ConsumerRecord<String, byte[]> record(long offset, CoreMessage event) {
        return new ConsumerRecord<>(TOPIC, PARTITION.partition(), offset, ProductLine.SPOT.name(),
                CoreMessageCodec.encode(event));
    }

    private static CoreMessage event(long sequence, List<CoreUserStateView> users) {
        UUID commandId = UUID.randomUUID();
        var event = new CoreExportEvent(sequence, sequence, sequence * 17, commandId,
                CoreMessageType.PROBE_INCREMENT, ResponseStatus.APPLIED, CoreResultCode.NONE,
                users.getFirst().userId(), new byte[] {(byte) sequence}, users, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), (sequence - 1) * 17, 0, 0,
                com.surprising.aeron.protocol.CoreRoute.DEFAULT.version(), 1, sequence * 17, sequence,
                com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0),
                sequence, List.of());
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId,
                ProductLine.SPOT, CommandSource.OPERATIONS, 1, sequence, users.getFirst().userId(),
                1_700_000_000_000L + sequence, 1).exportEvent(sequence), CoreExportCodec.encodeEvent(event));
    }

    private static CoreUserStateView user(long userId) {
        return new CoreUserStateView(ProductLine.SPOT, userId, 1, CorePositionMode.ONE_WAY,
                List.of(new CoreBalanceView("USDT", 100, 0)), List.of(), List.of());
    }

    private static JdbcDataSource dataSource(String name) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String resource : List.of(
                    "/db/migration/V001__create_core_event_projection.sql",
                    "/db/migration/V002__create_core_state_projections.sql",
                    "/db/migration/V003__create_core_funding_projections.sql",
                    "/db/migration/V004__create_core_liquidation_projections.sql",
                    "/db/migration/V005__enrich_core_execution_projection.sql",
                    "/db/migration/V006__enrich_core_liquidation_projection.sql",
                    "/db/migration/V007__add_projection_watermark_and_websocket_audit.sql",
                    "/db/migration/V008__drop_websocket_audit_projection.sql",
                    "/db/migration/V009__add_core_fact_funds.sql")) {
                try (var stream = KafkaProjectionWorkerTest.class.getResourceAsStream(resource)) {
                    String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    for (String sql : migration.split(";")) if (!sql.isBlank()) statement.execute(sql);
                }
            }
        }
        return dataSource;
    }

    private static void assertProjectionState(JdbcDataSource dataSource, int events, int facts,
                                              long watermark) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertCount(statement, "core_event_projection", events);
            assertCount(statement, "core_user_fact_projection", facts);
            try (var result = statement.executeQuery("SELECT last_export_sequence "
                    + "FROM core_projection_watermark WHERE product_line = 'SPOT'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(watermark);
            }
        }
    }

    private static void assertCount(java.sql.Statement statement, String table, int expected) throws Exception {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(expected);
        }
    }

    private static final class TrackingConsumer extends MockConsumer<String, byte[]> {

        private final JdbcDataSource dataSource;
        private int commitCalls;
        private boolean projectionVisibleAtCommit;

        private TrackingConsumer(JdbcDataSource dataSource) {
            super("earliest");
            this.dataSource = dataSource;
        }

        @Override
        public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            commitCalls++;
            try {
                assertProjectionState(dataSource, 1, 1, 1);
                projectionVisibleAtCommit = true;
            } catch (Exception exception) {
                throw new AssertionError("projection transaction was not visible before offset commit", exception);
            }
            super.commitSync(offsets);
        }
    }
}
