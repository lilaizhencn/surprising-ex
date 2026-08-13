package com.surprising.aeron.exporter;

import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
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
                 taker_user_id, maker_user_id, price_ticks, quantity_steps)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                insertFacts(connection, productLine, event);
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

    private static void insertFacts(Connection connection, ProductLine productLine,
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
                executions.setLong(8, execution.priceTicks());
                executions.setLong(9, execution.quantitySteps());
                executions.addBatch();
            }
            executions.executeBatch();
        }
    }

    private static void setClientOrderId(PreparedStatement statement, int index, String clientOrderId)
            throws SQLException {
        if (clientOrderId.isEmpty()) statement.setNull(index, java.sql.Types.VARCHAR);
        else statement.setString(index, clientOrderId);
    }
}
