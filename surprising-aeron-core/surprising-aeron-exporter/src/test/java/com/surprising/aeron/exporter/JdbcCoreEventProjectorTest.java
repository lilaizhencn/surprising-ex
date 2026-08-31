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
import com.surprising.aeron.protocol.CoreFundingPaymentView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
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
                    "/db/migration/V002__create_core_state_projections.sql",
                    "/db/migration/V004__create_core_liquidation_projections.sql",
                    "/db/migration/V005__enrich_core_execution_projection.sql",
                    "/db/migration/V006__enrich_core_liquidation_projection.sql",
                    "/db/migration/V007__add_projection_watermark_and_websocket_audit.sql",
                    "/db/migration/V008__drop_websocket_audit_projection.sql",
                    "/db/migration/V009__add_core_fact_funds.sql",
                    "/db/migration/V010__add_product_transfer_history.sql")) {
                try (var stream = getClass().getResourceAsStream(resource)) {
                    String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    for (String sql : migration.split(";")) {
                        if (!sql.isBlank()) statement.execute(sql);
                    }
                }
            }
        }
        CoreMessage event;
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            state.apply(new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 1, 1, 0, 1, 1),
                    CoreProtocol.probePayload(1)));
            event = awaitFirstExport(state);
        }
        JdbcCoreEventProjector projector = projector(dataSource);

        assertThat(projector.project(ProductLine.SPOT, event)).isTrue();
        assertThat(projector.project(ProductLine.SPOT, event)).isFalse();
        try (Connection connection = dataSource.getConnection(); var result = connection.createStatement()
                .executeQuery("SELECT COUNT(*) FROM core_event_projection")) {
            result.next();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void transferInFactCreatesOnlyCompletedPostgresqlHistory() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:transfer_history;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        migrate(dataSource);
        UUID commandId = UUID.randomUUID();
        var transfer = new TransferFundsCommand(7001L, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                "FUNDING", "USDT_PERPETUAL", "USDT", 1_250L, "transfer-007", "history test");
        var event = event(ProductLine.LINEAR_PERPETUAL, 1, 1, 9, commandId, CoreMessageType.TRANSFER_IN,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 42,
                TradingCommandCodec.encodeTransferFunds(transfer), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), 0, CoreExportEvent.Tombstones.empty());
        var message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.TRANSFER_IN, commandId,
                ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 1, 1, 42,
                1_700_000_000_000L, 1).exportEvent(1), CoreExportCodec.encodeEvent(event));

        assertThat(projector(dataSource).project(ProductLine.LINEAR_PERPETUAL, message)).isTrue();
        assertThat(projector(dataSource).project(ProductLine.LINEAR_PERPETUAL, message)).isFalse();
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT transfer_id, user_id, source_account_type, "
                     + "target_account_type, asset, amount_units, reference_id, status "
                     + "FROM account_product_transfers")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(7001L);
            assertThat(result.getLong(2)).isEqualTo(42L);
            assertThat(result.getString(3)).isEqualTo("FUNDING");
            assertThat(result.getString(4)).isEqualTo("USDT_PERPETUAL");
            assertThat(result.getString(5)).isEqualTo("USDT");
            assertThat(result.getLong(6)).isEqualTo(1_250L);
            assertThat(result.getString(7)).isEqualTo("transfer-007");
            assertThat(result.getString(8)).isEqualTo("COMPLETED");
            assertThat(result.next()).isFalse();
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
        var maker = new CoreOrderStateView(72, ProductLine.SPOT, 18, "BTC-USDT", 3,
                CoreOrderSide.SELL, 60_000, 2, 1, 1, false, "OPEN", 2);
        var execution = new CoreExecutionView(71, 72, 17, 18, 60_000, 1);
        var event = event(ProductLine.SPOT, 1, 1, 9, commandId, CoreMessageType.PLACE_ORDER,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 17, new byte[] {1},
                java.util.List.of(user), java.util.List.of(order, maker), java.util.List.of(execution),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), 0,
                CoreExportEvent.Tombstones.empty());
        var message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, commandId,
                ProductLine.SPOT, CommandSource.GATEWAY, 1, 1, 17, 1, 1).exportEvent(1),
                CoreExportCodec.encodeEvent(event));
        var projector = projector(dataSource);

        assertThat(projector.project(ProductLine.SPOT, message)).isTrue();
        assertThat(projector.project(ProductLine.SPOT, message)).isFalse();
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertCount(statement, "core_event_projection", 1);
            assertCount(statement, "core_user_fact_projection", 1);
            assertCount(statement, "core_order_projection", 2);
            assertCount(statement, "core_execution_projection", 1);
        }
    }

    @Test
    void acceptsAnUnchangedOrderRevisionInTheNextContiguousEvent() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:unchanged_order_revision;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        migrate(dataSource);
        var order = new CoreOrderStateView(71, ProductLine.SPOT, 17, "BTC-USDT", 3,
                CoreOrderSide.BUY, 60_000, 2, 0, 2, false, "OPEN", 1);
        CoreMessage first = orderEvent(1, CoreMessageType.PLACE_ORDER, order);
        CoreMessage second = orderEvent(2, CoreMessageType.CANCEL_ORDER_BATCH, order);
        var projector = projector(dataSource);

        assertThat(projector.project(ProductLine.SPOT, first)).isTrue();
        assertThat(projector.project(ProductLine.SPOT, second)).isTrue();

        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertCount(statement, "core_event_projection", 2);
            assertCount(statement, "core_order_projection", 1);
            try (var result = statement.executeQuery("SELECT order_revision, export_sequence "
                    + "FROM core_order_projection WHERE product_line = 'SPOT' AND order_id = 71")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(1);
                assertThat(result.getLong(2)).isEqualTo(1);
            }
        }
    }

    @Test
    void projectsFundingSettlementAndPaymentsFromAuthoritativeEvent() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:funding_projection;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        migrate(dataSource);
        UUID commandId = UUID.randomUUID();
        var command = new ApplyFundingCommand(81, "BTC-USDT", 7, 100);
        var longPayment = new CoreFundingPaymentView(81, 17, "BTC-USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, "USDT", 2, 120_000, 100, -12);
        var shortPayment = new CoreFundingPaymentView(81, 18, "BTC-USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, "USDT", -2, 120_000, 100, 12);
        var event = event(ProductLine.LINEAR_PERPETUAL, 1, 3, 9, commandId, CoreMessageType.APPLY_FUNDING,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 0, TradingCommandCodec.encodeApplyFunding(command),
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(longPayment, shortPayment), java.util.List.of(), java.util.List.of(), 0,
                CoreExportEvent.Tombstones.empty());
        var message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.APPLY_FUNDING, commandId,
                ProductLine.LINEAR_PERPETUAL, CommandSource.SCHEDULER, 1, 1, 0, 1234, 1).exportEvent(1),
                CoreExportCodec.encodeEvent(event));

        assertThat(projector(dataSource).project(ProductLine.LINEAR_PERPETUAL, message)).isTrue();
        var continuationCommand = new ApplyFundingCommand(81, "BTC-USDT", 7, 100, 18, 128);
        var continuationPayment = new CoreFundingPaymentView(81, 19, "BTC-USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, "USDT", 1, 60_000, 100, -6);
        var continuation = event(ProductLine.LINEAR_PERPETUAL, 2, 4, 10, UUID.randomUUID(), CoreMessageType.APPLY_FUNDING,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 0,
                TradingCommandCodec.encodeApplyFunding(continuationCommand), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(continuationPayment),
                java.util.List.of(), java.util.List.of(), 9, CoreExportEvent.Tombstones.empty());
        var continuationMessage = new CoreMessage(CoreMessageHeader.command(CoreMessageType.APPLY_FUNDING,
                continuation.commandId(), ProductLine.LINEAR_PERPETUAL, CommandSource.SCHEDULER, 1, 4, 0,
                1234, 1).exportEvent(2), CoreExportCodec.encodeEvent(continuation));
        assertThat(projector(dataSource).project(ProductLine.LINEAR_PERPETUAL,
                continuationMessage)).isTrue();
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertCount(statement, "core_funding_settlement_projection", 1);
            assertCount(statement, "core_funding_payment_projection", 3);
            try (var result = statement.executeQuery("SELECT total_long_payment_units, total_short_payment_units "
                    + "FROM core_funding_settlement_projection")) {
                result.next();
                assertThat(result.getLong(1)).isEqualTo(-18);
                assertThat(result.getLong(2)).isEqualTo(12);
            }
        }
    }

    @Test
    void projectsAuthoritativeLiquidationAndTreasuryFacts() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:liquidation_projection;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        migrate(dataSource);
        UUID commandId = UUID.randomUUID();
        var liquidation = new com.surprising.aeron.protocol.CoreLiquidationView(9, 17, "BTC-USDT", "USDT",
                com.surprising.aeron.protocol.CoreMarginMode.ISOLATED, CorePositionSide.NET,
                3, 8, 2, 2, 12, 60_000, 25_000, 3, "INSURANCE_REQUIRED");
        var treasury = new com.surprising.aeron.protocol.CoreTreasuryAssetView("USDT", 4, 9, 12);
        var event = event(ProductLine.LINEAR_PERPETUAL, 1, 4, 10, commandId, CoreMessageType.EXECUTE_LIQUIDATION,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 17,
                TradingCommandCodec.encodeExecuteLiquidation(
                        new com.surprising.aeron.protocol.ExecuteLiquidationCommand(9, 1, 60_000, 25_000)),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(liquidation), java.util.List.of(treasury), 0,
                CoreExportEvent.Tombstones.empty());
        var message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.EXECUTE_LIQUIDATION, commandId,
                ProductLine.LINEAR_PERPETUAL, CommandSource.SCHEDULER, 1, 1, 17, 1234, 1).exportEvent(1),
                CoreExportCodec.encodeEvent(event));

        assertThat(projector(dataSource).project(ProductLine.LINEAR_PERPETUAL, message)).isTrue();
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertCount(statement, "core_liquidation_projection", 1);
            assertCount(statement, "core_treasury_projection", 1);
            try (var result = statement.executeQuery("SELECT status, deficit_units, margin_mode, "
                    + "execution_price_ticks, liquidation_fee_rate_ppm, liquidation_fee_units "
                    + "FROM core_liquidation_projection")) {
                result.next();
                assertThat(result.getString(1)).isEqualTo("INSURANCE_REQUIRED");
                assertThat(result.getLong(2)).isEqualTo(12);
                assertThat(result.getString(3)).isEqualTo("ISOLATED");
                assertThat(result.getLong(4)).isEqualTo(60_000);
                assertThat(result.getLong(5)).isEqualTo(25_000);
                assertThat(result.getLong(6)).isEqualTo(3);
            }
        }
    }

    private static void migrate(JdbcDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String resource : java.util.List.of("/db/migration/V001__create_core_event_projection.sql",
                    "/db/migration/V002__create_core_state_projections.sql",
                    "/db/migration/V003__create_core_funding_projections.sql",
                    "/db/migration/V004__create_core_liquidation_projections.sql",
                    "/db/migration/V005__enrich_core_execution_projection.sql",
                    "/db/migration/V006__enrich_core_liquidation_projection.sql",
                    "/db/migration/V007__add_projection_watermark_and_websocket_audit.sql",
                    "/db/migration/V008__drop_websocket_audit_projection.sql",
                    "/db/migration/V009__add_core_fact_funds.sql",
                    "/db/migration/V010__add_product_transfer_history.sql")) {
                try (var stream = JdbcCoreEventProjectorTest.class.getResourceAsStream(resource)) {
                    String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    for (String sql : migration.split(";")) if (!sql.isBlank()) statement.execute(sql);
                }
            }
        }
    }

    private static CoreMessage orderEvent(long exportSequence, CoreMessageType type,
                                          CoreOrderStateView order) {
        UUID commandId = UUID.randomUUID();
        var event = event(ProductLine.SPOT, exportSequence, exportSequence, exportSequence * 17,
                commandId, type, ResponseStatus.APPLIED, CoreResultCode.NONE, order.userId(),
                new byte[] {(byte) exportSequence}, java.util.List.of(), java.util.List.of(order),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                (exportSequence - 1) * 17, CoreExportEvent.Tombstones.empty());
        return new CoreMessage(CoreMessageHeader.command(type, commandId, ProductLine.SPOT,
                CommandSource.GATEWAY, 1, exportSequence, order.userId(), exportSequence, exportSequence)
                .exportEvent(exportSequence), CoreExportCodec.encodeEvent(event));
    }

    private static JdbcCoreEventProjector projector(JdbcDataSource dataSource) {
        return new JdbcCoreEventProjector(dataSource);
    }

    private static CoreMessage awaitFirstExport(CoreProbeState state) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            CoreMessage query = new CoreMessage(CoreMessageHeader.query(
                    CoreMessageType.EXPORT_BATCH_QUERY, UUID.randomUUID(), ProductLine.SPOT,
                    CommandSource.OPERATIONS, 1, 0, 0, 1, 2), CoreExportCodec.encodeBatchQuery(1));
            java.util.List<CoreMessage> events = CoreExportCodec.decodeBatchResponse(
                    state.apply(query).data()).events();
            if (!events.isEmpty()) return events.getFirst();
            Thread.onSpinWait();
        }
        throw new IllegalStateException("Core Fact materialization did not complete");
    }

    @Test
    void appliesDeleteThenRecreateAsTheCanonicalChangedOrderFact() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:delete_recreate_order;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        migrate(dataSource);
        UUID commandId = UUID.randomUUID();
        var recreated = new CoreOrderStateView(71, ProductLine.SPOT, 17, "BTC-USDT", 3,
                CoreOrderSide.BUY, 60_000, 2, 0, 2, false, "OPEN", 2);
        var tombstones = new CoreExportEvent.Tombstones(java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(71L), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of());
        CoreExportEvent deleted = event(ProductLine.SPOT, 1, 1, 17, commandId, CoreMessageType.CANCEL_ORDER,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 17, new byte[] {1}, java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), 0, tombstones);
        CoreMessage deletedMessage = new CoreMessage(CoreMessageHeader.command(CoreMessageType.CANCEL_ORDER, commandId,
                ProductLine.SPOT, CommandSource.GATEWAY, 1, 1, 17, 1, 1).exportEvent(1),
                CoreExportCodec.encodeEvent(deleted));
        UUID recreateCommandId = UUID.randomUUID();
        CoreExportEvent recreatedEvent = event(ProductLine.SPOT, 2, 2, 34, recreateCommandId,
                CoreMessageType.PLACE_ORDER, ResponseStatus.APPLIED, CoreResultCode.NONE, 17, new byte[] {2},
                java.util.List.of(), java.util.List.of(recreated), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), 17, CoreExportEvent.Tombstones.empty());
        CoreMessage recreatedMessage = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER,
                recreateCommandId, ProductLine.SPOT, CommandSource.GATEWAY, 1, 2, 17, 2, 2).exportEvent(2),
                CoreExportCodec.encodeEvent(recreatedEvent));

        JdbcCoreEventProjector projector = projector(dataSource);
        assertThat(projector.project(ProductLine.SPOT, deletedMessage)).isTrue();
        assertThat(projector.project(ProductLine.SPOT, recreatedMessage)).isTrue();

        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT status, order_revision FROM core_order_projection "
                     + "WHERE product_line = 'SPOT' AND order_id = 71")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("OPEN");
            assertThat(result.getLong(2)).isEqualTo(2);
            assertThat(result.next()).isFalse();
        }
    }

    private static CoreExportEvent event(ProductLine productLine, long exportSequence, long appliedCommandCount,
                                         long businessStateHash, UUID commandId, CoreMessageType commandType,
                                         ResponseStatus status, CoreResultCode resultCode, long userId, byte[] payload,
                                         java.util.List<CoreUserStateView> users,
                                         java.util.List<CoreOrderStateView> orders,
                                         java.util.List<CoreExecutionView> executions,
                                         java.util.List<CoreFundingPaymentView> fundingPayments,
                                         java.util.List<com.surprising.aeron.protocol.CoreLiquidationView> liquidations,
                                         java.util.List<com.surprising.aeron.protocol.CoreTreasuryAssetView> treasury,
                                         long beforeBusinessStateHash, CoreExportEvent.Tombstones tombstones) {
        CoreMessage command = new CoreMessage(CoreMessageHeader.command(commandType, commandId, productLine,
                CommandSource.GATEWAY, 1, appliedCommandCount, userId, exportSequence, exportSequence), payload);
        return new CoreExportEvent(exportSequence, appliedCommandCount, businessStateHash, commandId, commandType,
                status, resultCode, userId, payload, users, orders, executions, fundingPayments, liquidations,
                treasury, java.util.List.of(), beforeBusinessStateHash, 0, 0,
                com.surprising.aeron.protocol.CoreRoute.DEFAULT.version(), 1,
                17, appliedCommandCount,
                com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0), exportSequence,
                java.util.List.of(), com.surprising.aeron.protocol.CommandFingerprint.of(command), java.util.List.of(),
                CoreExportEvent.TerminalIds.empty(), Math.max(0, appliedCommandCount - 1), appliedCommandCount,
                Math.max(0, exportSequence - 1), exportSequence, null, null, tombstones);
    }

    private static void assertCount(java.sql.Statement statement, String table, int expected) throws Exception {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            assertThat(result.getInt(1)).isEqualTo(expected);
        }
    }
}
