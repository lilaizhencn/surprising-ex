package com.surprising.funding.provider.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class FundingAccountCommandOutboxRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private FundingProperties properties;
    private FundingAccountCommandOutboxRepository repository;

    @BeforeEach
    void setUp() {
        properties = new FundingProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        repository = new FundingAccountCommandOutboxRepository(
                jdbcTemplate, new ObjectMapper(), properties);
    }

    @Test
    void enqueuesProductScopedCommandWithCanonicalUserKey() {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        AccountUserCommand command = command(ProductLine.LINEAR_PERPETUAL, 42L);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        repository.enqueue(9001L, command, now);

        verify(jdbcTemplate).update(contains("INSERT INTO account_outbox_events"),
                eq("LINEAR_PERPETUAL"), eq(9001L),
                eq("surprising.linear-perp.account.user.commands.v1"),
                eq("LINEAR_PERPETUAL:42"), eq("FUNDING_SETTLE"), anyString(),
                any(java.sql.Timestamp.class), any(java.sql.Timestamp.class),
                any(java.sql.Timestamp.class));
    }

    @Test
    void rejectsProductLineMismatchBeforeWritingOutbox() {
        AccountUserCommand command = command(ProductLine.INVERSE_PERPETUAL, 42L);

        assertThatThrownBy(() -> repository.enqueue(9001L, command, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product line mismatch");

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void failsTransactionWhenOutboxInsertIsSkipped() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> repository.enqueue(
                9001L, command(ProductLine.LINEAR_PERPETUAL, 42L), Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to enqueue funding account command");
    }

    private AccountUserCommand command(ProductLine productLine, long userId) {
        return new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                "FUNDING:" + productLine + ":9001:" + userId,
                productLine,
                userId,
                AccountUserCommandType.FUNDING_SETTLE,
                "FUNDING",
                "9001",
                null,
                "{\"paymentId\":9001}",
                Instant.parse("2026-07-18T00:00:00Z"),
                "trace-9001");
    }
}
