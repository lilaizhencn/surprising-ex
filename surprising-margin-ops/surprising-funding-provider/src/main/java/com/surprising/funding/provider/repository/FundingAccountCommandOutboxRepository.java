package com.surprising.funding.provider.repository;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.funding.provider.config.FundingProperties;
import java.sql.Timestamp;
import java.time.Instant;
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
}
