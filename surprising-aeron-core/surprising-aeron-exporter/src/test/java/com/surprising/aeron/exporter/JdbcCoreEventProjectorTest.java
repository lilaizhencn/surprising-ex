package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
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
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V001__create_core_event_projection.sql")) {
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String sql : migration.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
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
}
