package com.surprising.funding.provider.repository;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.funding.provider.config.FundingProperties;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores account commands in the shared durable outbox in the same transaction as funding rows.
 */
@Repository
public class FundingAccountCommandOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final FundingProperties properties;

    public FundingAccountCommandOutboxRepository(JdbcTemplate jdbcTemplate,
                                                ObjectMapper objectMapper,
                                                FundingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void enqueue(long paymentId, AccountUserCommand command, Instant now) {
        if (command.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("funding account command product line mismatch");
        }
        int rows = jdbcTemplate.update("""
                INSERT INTO account_outbox_events (
                    product_line, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, 'FUNDING_PAYMENT_COMMAND', ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, command.productLine().name(), paymentId, properties.getKafka().getUserCommandsTopic(),
                command.partitionKey(), command.commandType().name(), objectMapper.writeValueAsString(command),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (rows != 1) {
            throw new IllegalStateException("failed to enqueue funding account command " + command.commandId());
        }
    }

    public void enqueueBatch(List<FundingAccountCommandWrite> commands, Instant now) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        List<SerializedCommand> serialized = new ArrayList<>(commands.size());
        for (FundingAccountCommandWrite write : commands) {
            AccountUserCommand command = write.command();
            if (command.productLine() != properties.getKafka().getProductLine()) {
                throw new IllegalArgumentException("funding account command product line mismatch");
            }
            serialized.add(new SerializedCommand(write.paymentId(), command,
                    objectMapper.writeValueAsString(command)));
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO account_outbox_events (
                    product_line, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, 'FUNDING_PAYMENT_COMMAND', ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                SerializedCommand write = serialized.get(index);
                AccountUserCommand command = write.command();
                statement.setString(1, command.productLine().name());
                statement.setLong(2, write.paymentId());
                statement.setString(3, properties.getKafka().getUserCommandsTopic());
                statement.setString(4, command.partitionKey());
                statement.setString(5, command.commandType().name());
                statement.setString(6, write.payload());
                statement.setTimestamp(7, Timestamp.from(now));
                statement.setTimestamp(8, Timestamp.from(now));
                statement.setTimestamp(9, Timestamp.from(now));
            }

            @Override
            public int getBatchSize() {
                return serialized.size();
            }
        });
        if (rows.length != serialized.size()) {
            throw new IllegalStateException("failed to enqueue funding account command batch");
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("failed to enqueue funding account command batch");
            }
        }
    }

    public record FundingAccountCommandWrite(long paymentId, AccountUserCommand command) {
    }

    private record SerializedCommand(long paymentId, AccountUserCommand command, String payload) {
    }
}
