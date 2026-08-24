package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class JdbcCoreEventProjectorPostgresTest {

    private static final String IMAGE = System.getProperty(
            "surprising.test.postgres.image",
            System.getenv().getOrDefault("SURPRISING_TEST_POSTGRES_IMAGE", "postgres:18"));
    private static final String PASSWORD = "projection-test";
    private static String containerId;
    private static PGSimpleDataSource dataSource;

    @BeforeAll
    static void startPostgres() throws Exception {
        containerId = command("docker", "run", "--detach", "--rm",
                "--env", "POSTGRES_PASSWORD=" + PASSWORD,
                "--env", "POSTGRES_DB=projection",
                "--publish", "127.0.0.1::5432", IMAGE).lines()
                .filter(line -> !line.isBlank()).reduce((first, last) -> last).orElseThrow();
        String port = command("docker", "port", containerId, "5432/tcp").trim();
        dataSource = new PGSimpleDataSource();
        dataSource.setURL("jdbc:postgresql://127.0.0.1:" + port.substring(port.lastIndexOf(':') + 1)
                + "/projection");
        dataSource.setUser("postgres");
        dataSource.setPassword(PASSWORD);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        SQLException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try (Connection ignored = dataSource.getConnection()) {
                migrate();
                migrateResource("/db/migration/V007__add_projection_watermark_and_websocket_audit.sql");
                assertSeededProductLines();
                return;
            } catch (SQLException exception) {
                lastFailure = exception;
                Thread.sleep(100);
            }
        }
        throw new SQLException("PostgreSQL did not become ready", lastFailure);
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (containerId != null) command("docker", "rm", "--force", containerId);
    }

    @BeforeEach
    void clearProjection() throws Exception {
        dropFaultInjection();
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("TRUNCATE core_funding_payment_projection, core_funding_settlement_projection, "
                    + "core_execution_projection, core_order_projection, core_user_fact_projection, "
                    + "core_liquidation_projection, core_treasury_projection, core_event_projection");
            statement.executeUpdate("UPDATE core_projection_watermark SET last_export_sequence = 0");
        }
    }

    @AfterEach
    void removeFaultInjection() throws Exception {
        dropFaultInjection();
    }

    @Test
    void commitsContiguousFactsAndWatermark() throws Exception {
        CoreMessage first = event(1, UUID.randomUUID(), List.of(user(17, 1)));
        JdbcCoreEventProjector projector = new JdbcCoreEventProjector(dataSource);

        assertThat(projector.project(ProductLine.SPOT, first)).isTrue();
        assertProjectionState(1, 1, 1);
        assertThat(projector.project(ProductLine.SPOT, first)).isFalse();
        assertProjectionState(1, 1, 1);

        CoreMessage second = event(2, UUID.randomUUID(), List.of(user(18, 1)));
        assertThat(new JdbcCoreEventProjector(dataSource).project(ProductLine.SPOT, second)).isTrue();
        assertProjectionState(2, 2, 2);
        assertThat(projector.project(ProductLine.SPOT, first)).isFalse();
        assertProjectionState(2, 2, 2);
    }

    @Test
    void rollsBackGapConflictAndFactFailure() throws Exception {
        UUID commandId = UUID.randomUUID();
        CoreMessage first = event(1, commandId, List.of(user(17, 1)));
        JdbcCoreEventProjector projector = new JdbcCoreEventProjector(dataSource);
        assertThat(projector.project(ProductLine.SPOT, first)).isTrue();

        assertThatThrownBy(() -> projector.project(ProductLine.SPOT,
                event(3, UUID.randomUUID(), List.of(user(19, 1)))))
                .isInstanceOf(SQLException.class).hasMessageContaining("sequence gap");
        assertProjectionState(1, 1, 1);

        assertThatThrownBy(() -> projector.project(ProductLine.SPOT,
                event(1, commandId, List.of(user(17, 2)))))
                .isInstanceOf(SQLException.class).hasMessageContaining("conflicting duplicate");
        assertProjectionState(1, 1, 1);

        CoreUserStateView duplicate = user(20, 1);
        assertThatThrownBy(() -> projector.project(ProductLine.SPOT,
                event(2, UUID.randomUUID(), List.of(duplicate, duplicate))))
                .isInstanceOf(SQLException.class);
        assertProjectionState(1, 1, 1);
    }

    @Test
    void rollsBackWatermarkUpdateFailure() throws Exception {
        installFaultInjection("fail_watermark_update", "core_projection_watermark", "UPDATE");

        assertThatThrownBy(() -> new JdbcCoreEventProjector(dataSource).project(ProductLine.SPOT,
                event(1, UUID.randomUUID(), List.of(user(32, 1)))))
                .isInstanceOf(SQLException.class).hasMessageContaining("injected projection failure");
        assertProjectionState(0, 0, 0);
    }

    private static CoreMessage event(long sequence, UUID commandId, List<CoreUserStateView> users) {
        var event = new CoreExportEvent(sequence, sequence, sequence * 17, commandId,
                CoreMessageType.PROBE_INCREMENT, ResponseStatus.APPLIED, CoreResultCode.NONE,
                users.getFirst().userId(), new byte[] {(byte) sequence}, users, List.of(), List.of());
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId,
                ProductLine.SPOT, CommandSource.OPERATIONS, 1, sequence, users.getFirst().userId(),
                1_700_000_000_000L + sequence, 1).exportEvent(sequence), CoreExportCodec.encodeEvent(event));
    }

    private static CoreUserStateView user(long userId, long revision) {
        return new CoreUserStateView(ProductLine.SPOT, userId, revision, CorePositionMode.ONE_WAY,
                List.of(new CoreBalanceView("USDT", 100, 0)), List.of(), List.of());
    }

    private static void assertProjectionState(int events, int facts, long watermark)
            throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertCount(statement, "core_event_projection", events);
            assertCount(statement, "core_user_fact_projection", facts);
            try (var result = statement.executeQuery("SELECT last_export_sequence "
                    + "FROM core_projection_watermark WHERE product_line = 'SPOT'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(watermark);
                assertThat(result.next()).isFalse();
            }
        }
    }

    private static void assertCount(java.sql.Statement statement, String table, int expected) throws Exception {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(expected);
        }
    }

    private static void assertSeededProductLines() throws Exception {
        List<String> actual = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT product_line FROM core_projection_watermark ORDER BY product_line")) {
            while (result.next()) actual.add(result.getString(1));
        }
        assertThat(actual).containsExactly(
                "INVERSE_DELIVERY", "INVERSE_PERPETUAL", "LINEAR_DELIVERY",
                "LINEAR_PERPETUAL", "OPTION", "SPOT");
    }

    private static void installFaultInjection(String trigger, String table, String operation) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE OR REPLACE FUNCTION fail_projection_write() RETURNS trigger AS $$
                    BEGIN
                        RAISE EXCEPTION 'injected projection failure';
                    END;
                    $$ LANGUAGE plpgsql
                    """);
            statement.execute("CREATE TRIGGER " + trigger + " BEFORE " + operation + " ON " + table
                    + " FOR EACH ROW EXECUTE FUNCTION fail_projection_write()");
        }
    }

    private static void dropFaultInjection() throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP FUNCTION IF EXISTS fail_projection_write() CASCADE");
        }
    }

    private static void migrate() throws Exception {
        for (int version = 1; version <= 7; version++) {
            String prefix = "/db/migration/V%03d__".formatted(version);
            String resource = switch (version) {
                case 1 -> prefix + "create_core_event_projection.sql";
                case 2 -> prefix + "create_core_state_projections.sql";
                case 3 -> prefix + "create_core_funding_projections.sql";
                case 4 -> prefix + "create_core_liquidation_projections.sql";
                case 5 -> prefix + "enrich_core_execution_projection.sql";
                case 6 -> prefix + "enrich_core_liquidation_projection.sql";
                case 7 -> prefix + "add_projection_watermark_and_websocket_audit.sql";
                default -> throw new IllegalStateException("unexpected migration version");
            };
            migrateResource(resource);
        }
    }

    private static void migrateResource(String resource) throws Exception {
        try (var stream = JdbcCoreEventProjectorPostgresTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                for (String sql : migration.split(";")) if (!sql.isBlank()) statement.execute(sql);
            }
        }
    }

    private static String command(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException(String.join(" ", command) + " failed: " + output);
        return output;
    }
}
