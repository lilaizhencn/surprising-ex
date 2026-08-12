package com.surprising.insurance.provider.repository;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.insurance.provider.config.InsuranceProperties;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * 仅负责 account_outbox_events 表中的保险基金账户命令。
 */
@Repository
public class InsuranceAccountOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final InsuranceProperties properties;
    private final ObjectMapper objectMapper;

    public InsuranceAccountOutboxRepository(JdbcTemplate jdbcTemplate,
                                            InsuranceProperties properties,
                                            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void enqueue(long coverageId, AccountUserCommand command, Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO account_outbox_events (
                    product_line, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, 'INSURANCE_ACCOUNT_COMMAND', ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, command.productLine().name(), coverageId, properties.getKafka().getUserCommandsTopic(),
                command.partitionKey(), command.commandType().name(), objectMapper.writeValueAsString(command),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (rows != 1) {
            throw new IllegalStateException("failed to write insurance account command enqueue");
        }
    }
}
