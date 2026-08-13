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
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
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
            try {
                return statement.executeUpdate() == 1;
            } catch (SQLException exception) {
                if ("23505".equals(exception.getSQLState())) {
                    return false;
                }
                throw exception;
            }
        }
    }
}
