package com.surprising.aeron.exporter;

import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.product.api.ProductLine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

public final class JdbcCoreEventProjector {

    private static final String INSERT = """
            INSERT INTO core_event_projection (
                product_line, export_sequence, applied_command_count, business_state_hash,
                command_id, command_type, command_status, result_code, user_id, raw_event
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_USER_FACT = """
            INSERT INTO core_user_fact_projection
                (product_line, export_sequence, user_id, user_revision, raw_user_delta)
            VALUES (?, ?, ?, ?, ?)
            """;
    private static final String INSERT_ORDER = """
            INSERT INTO core_order_projection
                (product_line, order_id, user_id, client_order_id, symbol, status,
                 created_at_epoch_ms, updated_at_epoch_ms, cluster_position, order_revision,
                 export_sequence, raw_order_state)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String UPDATE_ORDER = """
            UPDATE core_order_projection SET
                user_id = ?, client_order_id = ?, symbol = ?, status = ?,
                created_at_epoch_ms = ?, updated_at_epoch_ms = ?, cluster_position = ?,
                order_revision = ?, export_sequence = ?, raw_order_state = ?
            WHERE product_line = ? AND order_id = ? AND order_revision < ?
            """;
    private static final String INSERT_EXECUTION = """
            INSERT INTO core_execution_projection
                (product_line, export_sequence, execution_index, taker_order_id, maker_order_id,
                 taker_user_id, maker_user_id, symbol, instrument_version, taker_side,
                 taker_fee_rate_ppm, maker_fee_rate_ppm, price_ticks, quantity_steps, occurred_at_epoch_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_FUNDING_SETTLEMENT = """
            INSERT INTO core_funding_settlement_projection
                (product_line, settlement_id, export_sequence, symbol, instrument_version,
                 funding_rate_ppm, command_status, result_code, total_long_payment_units,
                 total_short_payment_units, position_count, occurred_at_epoch_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_FUNDING_PAYMENT = """
            INSERT INTO core_funding_payment_projection
                (product_line, export_sequence, payment_index, settlement_id, user_id, symbol,
                 margin_mode, position_side, asset, signed_quantity_steps, notional_units,
                 funding_rate_ppm, amount_units, occurred_at_epoch_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_LIQUIDATION = """
            INSERT INTO core_liquidation_projection
                (product_line, liquidation_id, user_id, symbol, asset, margin_mode, position_side,
                 instrument_version, trigger_price_sequence, signed_quantity_steps,
                 close_quantity_steps, deficit_units, execution_price_ticks,
                 liquidation_fee_rate_ppm, liquidation_fee_units, status,
                 export_sequence, updated_at_epoch_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String UPDATE_LIQUIDATION = """
            UPDATE core_liquidation_projection SET
                user_id = ?, symbol = ?, asset = ?, margin_mode = ?, position_side = ?, instrument_version = ?,
                trigger_price_sequence = ?, signed_quantity_steps = ?, close_quantity_steps = ?,
                deficit_units = ?, execution_price_ticks = ?, liquidation_fee_rate_ppm = ?,
                liquidation_fee_units = ?, status = ?,
                export_sequence = ?, updated_at_epoch_ms = ?
            WHERE product_line = ? AND liquidation_id = ? AND export_sequence < ?
            """;
    private static final String INSERT_TREASURY = """
            INSERT INTO core_treasury_projection
                (product_line, asset, fee_balance_units, insurance_balance_units,
                 insurance_deficit_units, export_sequence, updated_at_epoch_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String UPDATE_TREASURY = """
            UPDATE core_treasury_projection SET
                fee_balance_units = ?, insurance_balance_units = ?, insurance_deficit_units = ?,
                export_sequence = ?, updated_at_epoch_ms = ?
            WHERE product_line = ? AND asset = ? AND export_sequence < ?
            """;

    private final DataSource dataSource;

    public JdbcCoreEventProjector(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public boolean project(ProductLine productLine, CoreMessage message) throws SQLException {
        CoreExportEvent event = CoreExportCodec.decodeEvent(message.payload());
        if (message.header().productLine() != productLine
                || message.header().sourceSequence() != event.exportSequence()) {
            throw new IllegalArgumentException("export event identity mismatch");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertEvent(connection, productLine, message, event);
                insertFacts(connection, productLine, message, event);
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                if ("23505".equals(exception.getSQLState())) return false;
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void insertEvent(Connection connection, ProductLine productLine, CoreMessage message,
                                    CoreExportEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, productLine.name());
            statement.setLong(2, event.exportSequence());
            statement.setLong(3, event.appliedCommandCount());
            statement.setLong(4, event.businessStateHash());
            statement.setObject(5, event.commandId());
            statement.setString(6, event.commandType().name());
            statement.setString(7, event.commandStatus().name());
            statement.setString(8, event.resultCode().name());
            statement.setLong(9, event.userId());
            statement.setBytes(10, CoreMessageCodec.encode(message));
            if (statement.executeUpdate() != 1) throw new SQLException("core event projection was not inserted");
        }
    }

    private static void insertFacts(Connection connection, ProductLine productLine, CoreMessage message,
                                    CoreExportEvent event) throws SQLException {
        try (PreparedStatement users = connection.prepareStatement(INSERT_USER_FACT)) {
            for (var user : event.changedUsers()) {
                users.setString(1, productLine.name());
                users.setLong(2, event.exportSequence());
                users.setLong(3, user.userId());
                users.setLong(4, user.revision());
                users.setBytes(5, com.surprising.aeron.protocol.CoreStateQueryCodec.encodeUserState(user));
                users.addBatch();
            }
            users.executeBatch();
        }
        try (PreparedStatement update = connection.prepareStatement(UPDATE_ORDER);
             PreparedStatement insert = connection.prepareStatement(INSERT_ORDER)) {
            for (var order : event.changedOrders()) {
                byte[] raw = com.surprising.aeron.protocol.CoreStateQueryCodec.encodeOrderState(order);
                update.setLong(1, order.userId());
                setClientOrderId(update, 2, order.clientOrderId());
                update.setString(3, order.symbol());
                update.setString(4, order.status());
                update.setLong(5, order.createdAtEpochMillis());
                update.setLong(6, order.updatedAtEpochMillis());
                update.setLong(7, order.clusterPosition());
                update.setLong(8, order.revision());
                update.setLong(9, event.exportSequence());
                update.setBytes(10, raw);
                update.setString(11, productLine.name());
                update.setLong(12, order.orderId());
                update.setLong(13, order.revision());
                if (update.executeUpdate() == 0) {
                    insert.setString(1, productLine.name());
                    insert.setLong(2, order.orderId());
                    insert.setLong(3, order.userId());
                    setClientOrderId(insert, 4, order.clientOrderId());
                    insert.setString(5, order.symbol());
                    insert.setString(6, order.status());
                    insert.setLong(7, order.createdAtEpochMillis());
                    insert.setLong(8, order.updatedAtEpochMillis());
                    insert.setLong(9, order.clusterPosition());
                    insert.setLong(10, order.revision());
                    insert.setLong(11, event.exportSequence());
                    insert.setBytes(12, raw);
                    insert.executeUpdate();
                }
            }
        }
        try (PreparedStatement executions = connection.prepareStatement(INSERT_EXECUTION)) {
            for (int index = 0; index < event.executions().size(); index++) {
                var execution = event.executions().get(index);
                executions.setString(1, productLine.name());
                executions.setLong(2, event.exportSequence());
                executions.setInt(3, index);
                executions.setLong(4, execution.takerOrderId());
                executions.setLong(5, execution.makerOrderId());
                executions.setLong(6, execution.takerUserId());
                executions.setLong(7, execution.makerUserId());
                var taker = requireChangedOrder(event, execution.takerOrderId());
                var maker = requireChangedOrder(event, execution.makerOrderId());
                if (!taker.symbol().equals(maker.symbol())) {
                    throw new IllegalArgumentException("execution order symbols differ");
                }
                executions.setString(8, taker.symbol());
                executions.setLong(9, taker.instrumentVersion());
                executions.setString(10, taker.side().name());
                executions.setLong(11, taker.takerFeeRatePpm());
                executions.setLong(12, maker.makerFeeRatePpm());
                executions.setLong(13, execution.priceTicks());
                executions.setLong(14, execution.quantitySteps());
                executions.setLong(15, message.header().submittedAtEpochMillis());
                executions.addBatch();
            }
            executions.executeBatch();
        }
        insertFundingFacts(connection, productLine, message, event);
        upsertLiquidations(connection, productLine, message, event);
        upsertTreasury(connection, productLine, message, event);
    }

    private static com.surprising.aeron.protocol.CoreOrderStateView requireChangedOrder(
            CoreExportEvent event, long orderId) {
        return event.changedOrders().stream().filter(order -> order.orderId() == orderId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "execution order is missing from changed orders: " + orderId));
    }

    private static void upsertLiquidations(Connection connection, ProductLine productLine, CoreMessage message,
                                           CoreExportEvent event) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(UPDATE_LIQUIDATION);
             PreparedStatement insert = connection.prepareStatement(INSERT_LIQUIDATION)) {
            for (var liquidation : event.changedLiquidations()) {
                update.setLong(1, liquidation.userId());
                update.setString(2, liquidation.symbol());
                update.setString(3, liquidation.asset());
                update.setString(4, liquidation.marginMode().name());
                update.setString(5, liquidation.positionSide().name());
                update.setLong(6, liquidation.instrumentVersion());
                update.setLong(7, liquidation.triggerPriceSequence());
                update.setLong(8, liquidation.signedQuantitySteps());
                update.setLong(9, liquidation.closeQuantitySteps());
                update.setLong(10, liquidation.deficitUnits());
                update.setLong(11, liquidation.executionPriceTicks());
                update.setLong(12, liquidation.liquidationFeeRatePpm());
                update.setLong(13, liquidation.liquidationFeeUnits());
                update.setString(14, liquidation.status());
                update.setLong(15, event.exportSequence());
                update.setLong(16, message.header().submittedAtEpochMillis());
                update.setString(17, productLine.name());
                update.setLong(18, liquidation.liquidationId());
                update.setLong(19, event.exportSequence());
                if (update.executeUpdate() == 0) {
                    insert.setString(1, productLine.name());
                    insert.setLong(2, liquidation.liquidationId());
                    insert.setLong(3, liquidation.userId());
                    insert.setString(4, liquidation.symbol());
                    insert.setString(5, liquidation.asset());
                    insert.setString(6, liquidation.marginMode().name());
                    insert.setString(7, liquidation.positionSide().name());
                    insert.setLong(8, liquidation.instrumentVersion());
                    insert.setLong(9, liquidation.triggerPriceSequence());
                    insert.setLong(10, liquidation.signedQuantitySteps());
                    insert.setLong(11, liquidation.closeQuantitySteps());
                    insert.setLong(12, liquidation.deficitUnits());
                    insert.setLong(13, liquidation.executionPriceTicks());
                    insert.setLong(14, liquidation.liquidationFeeRatePpm());
                    insert.setLong(15, liquidation.liquidationFeeUnits());
                    insert.setString(16, liquidation.status());
                    insert.setLong(17, event.exportSequence());
                    insert.setLong(18, message.header().submittedAtEpochMillis());
                    insert.executeUpdate();
                }
            }
        }
    }

    private static void upsertTreasury(Connection connection, ProductLine productLine, CoreMessage message,
                                       CoreExportEvent event) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(UPDATE_TREASURY);
             PreparedStatement insert = connection.prepareStatement(INSERT_TREASURY)) {
            for (var treasury : event.changedTreasuryAssets()) {
                update.setLong(1, treasury.feeBalanceUnits());
                update.setLong(2, treasury.insuranceBalanceUnits());
                update.setLong(3, treasury.insuranceDeficitUnits());
                update.setLong(4, event.exportSequence());
                update.setLong(5, message.header().submittedAtEpochMillis());
                update.setString(6, productLine.name());
                update.setString(7, treasury.asset());
                update.setLong(8, event.exportSequence());
                if (update.executeUpdate() == 0) {
                    insert.setString(1, productLine.name());
                    insert.setString(2, treasury.asset());
                    insert.setLong(3, treasury.feeBalanceUnits());
                    insert.setLong(4, treasury.insuranceBalanceUnits());
                    insert.setLong(5, treasury.insuranceDeficitUnits());
                    insert.setLong(6, event.exportSequence());
                    insert.setLong(7, message.header().submittedAtEpochMillis());
                    insert.executeUpdate();
                }
            }
        }
    }

    private static void insertFundingFacts(Connection connection, ProductLine productLine, CoreMessage message,
                                           CoreExportEvent event) throws SQLException {
        if (event.commandType() != CoreMessageType.APPLY_FUNDING) return;
        var command = TradingCommandCodec.decodeApplyFunding(event.commandPayload());
        long totalLong = 0;
        long totalShort = 0;
        for (var payment : event.fundingPayments()) {
            if (payment.signedQuantitySteps() > 0) totalLong = Math.addExact(totalLong, payment.amountUnits());
            else totalShort = Math.addExact(totalShort, payment.amountUnits());
        }
        try (PreparedStatement settlement = connection.prepareStatement(INSERT_FUNDING_SETTLEMENT)) {
            settlement.setString(1, productLine.name());
            settlement.setLong(2, command.settlementId());
            settlement.setLong(3, event.exportSequence());
            settlement.setString(4, command.symbol());
            settlement.setLong(5, command.instrumentVersion());
            settlement.setLong(6, command.fundingRatePpm());
            settlement.setString(7, event.commandStatus().name());
            settlement.setString(8, event.resultCode().name());
            settlement.setLong(9, totalLong);
            settlement.setLong(10, totalShort);
            settlement.setInt(11, event.fundingPayments().size());
            settlement.setLong(12, message.header().submittedAtEpochMillis());
            settlement.executeUpdate();
        }
        try (PreparedStatement payments = connection.prepareStatement(INSERT_FUNDING_PAYMENT)) {
            for (int index = 0; index < event.fundingPayments().size(); index++) {
                var payment = event.fundingPayments().get(index);
                payments.setString(1, productLine.name());
                payments.setLong(2, event.exportSequence());
                payments.setInt(3, index);
                payments.setLong(4, payment.settlementId());
                payments.setLong(5, payment.userId());
                payments.setString(6, payment.symbol());
                payments.setString(7, payment.marginMode().name());
                payments.setString(8, payment.positionSide().name());
                payments.setString(9, payment.asset());
                payments.setLong(10, payment.signedQuantitySteps());
                payments.setLong(11, payment.notionalUnits());
                payments.setLong(12, payment.fundingRatePpm());
                payments.setLong(13, payment.amountUnits());
                payments.setLong(14, message.header().submittedAtEpochMillis());
                payments.addBatch();
            }
            payments.executeBatch();
        }
    }

    private static void setClientOrderId(PreparedStatement statement, int index, String clientOrderId)
            throws SQLException {
        if (clientOrderId.isEmpty()) statement.setNull(index, java.sql.Types.VARCHAR);
        else statement.setString(index, clientOrderId);
    }
}
