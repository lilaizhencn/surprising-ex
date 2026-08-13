package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreExecutionView;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.CoreProbeState;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcCoreEventProjectorTest {

    @Test
    void duplicateDeliveryCreatesOneProjectionRow() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:projection;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String resource : java.util.List.of("/db/migration/V001__create_core_event_projection.sql",
                    "/db/migration/V002__create_core_state_projections.sql")) {
                try (var stream = getClass().getResourceAsStream(resource)) {
                    String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    for (String sql : migration.split(";")) {
                        if (!sql.isBlank()) statement.execute(sql);
                    }
                }
            }
        }
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        state.apply(new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 1, 1, 0, 1, 1),
                CoreProtocol.probePayload(1)));
        CoreMessage event = CoreExportCodec.decodeBatch(state.apply(new CoreMessage(CoreMessageHeader.query(
                CoreMessageType.EXPORT_BATCH_QUERY, UUID.randomUUID(), ProductLine.SPOT,
                CommandSource.OPERATIONS, 1, 0, 0, 1, 2), CoreExportCodec.encodeBatchQuery(1))).data()).getFirst();
        JdbcCoreEventProjector projector = new JdbcCoreEventProjector(dataSource);

        assertThat(projector.project(ProductLine.SPOT, event)).isTrue();
        assertThat(projector.project(ProductLine.SPOT, event)).isFalse();
        try (Connection connection = dataSource.getConnection(); var result = connection.createStatement()
                .executeQuery("SELECT COUNT(*) FROM core_event_projection")) {
            result.next();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void projectsUserOrderAndExecutionFactsInOneIdempotentTransaction() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:state_projection;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        migrate(dataSource);
        UUID commandId = UUID.randomUUID();
        var user = new CoreUserStateView(ProductLine.SPOT, 17, 2, CorePositionMode.ONE_WAY,
                java.util.List.of(new CoreBalanceView("USDT", 900, 100)), java.util.List.of(), java.util.List.of());
        var order = new CoreOrderStateView(71, ProductLine.SPOT, 17, "BTC-USDT", 3,
                CoreOrderSide.BUY, 60_000, 2, 0, 2, false, "OPEN", 1);
        var execution = new CoreExecutionView(71, 72, 17, 18, 60_000, 1);
        var event = new CoreExportEvent(1, 1, 9, commandId, CoreMessageType.PLACE_ORDER,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 17, new byte[] {1},
                java.util.List.of(user), java.util.List.of(order), java.util.List.of(execution));
        var message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, commandId,
                ProductLine.SPOT, CommandSource.GATEWAY, 1, 1, 17, 1, 1).exportEvent(1),
                CoreExportCodec.encodeEvent(event));
        var projector = new JdbcCoreEventProjector(dataSource);

        assertThat(projector.project(ProductLine.SPOT, message)).isTrue();
        assertThat(projector.project(ProductLine.SPOT, message)).isFalse();
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertCount(statement, "core_event_projection", 1);
            assertCount(statement, "core_user_fact_projection", 1);
            assertCount(statement, "core_order_projection", 1);
            assertCount(statement, "core_execution_projection", 1);
        }
    }

    private static void migrate(JdbcDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String resource : java.util.List.of("/db/migration/V001__create_core_event_projection.sql",
                    "/db/migration/V002__create_core_state_projections.sql")) {
                try (var stream = JdbcCoreEventProjectorTest.class.getResourceAsStream(resource)) {
                    String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    for (String sql : migration.split(";")) if (!sql.isBlank()) statement.execute(sql);
                }
            }
        }
    }

    private static void assertCount(java.sql.Statement statement, String table, int expected) throws Exception {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            assertThat(result.getInt(1)).isEqualTo(expected);
        }
    }
}
