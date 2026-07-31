package com.surprising.adl.provider.repository;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.adl.provider.config.AdlProperties;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * 仅负责 account_outbox_events 表中的 ADL 账户命令。
 */
@Repository
public class AdlAccountOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AdlProperties properties;

    public AdlAccountOutboxRepository(JdbcTemplate jdbcTemplate,
                                      ObjectMapper objectMapper,
                                      AdlProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void enqueue(long executionId, AccountUserCommand command, Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO account_outbox_events (
                    product_line, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, 'ADL_ACCOUNT_COMMAND', ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, command.productLine().name(), executionId, properties.getKafka().getUserCommandsTopic(),
                command.partitionKey(), command.commandType().name(), objectMapper.writeValueAsString(command),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (rows != 1) {
            throw new IllegalStateException("failed to write ADL account command enqueue");
        }
    }
}
