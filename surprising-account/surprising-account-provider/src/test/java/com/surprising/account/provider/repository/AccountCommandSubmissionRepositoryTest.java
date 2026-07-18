package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.product.api.ProductLine;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AccountCommandSubmissionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Test
    void firstSubmissionOwnsTheIdempotencyKey() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AccountCommandSubmissionRepository repository =
                new AccountCommandSubmissionRepository(jdbcTemplate);
        AccountUserCommand command = command("{\"amountUnits\":100}");
        when(jdbcTemplate.update(contains("INSERT INTO account_command_submissions"),
                any(Object[].class))).thenReturn(1);

        assertThat(repository.register(command, "{\"commandId\":\"balance:deposit-1\"}", NOW)).isTrue();

        verify(jdbcTemplate).update(contains("ON CONFLICT (command_id) DO NOTHING"),
                any(Object[].class));
    }

    @Test
    void exactRetryDoesNotCreateAnotherOutboxOwner() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AccountCommandSubmissionRepository repository =
                new AccountCommandSubmissionRepository(jdbcTemplate);
        AccountUserCommand command = command("{\"amountUnits\":100}");
        AtomicReference<String> insertedIdentityHash = new AtomicReference<>();
        when(jdbcTemplate.update(contains("INSERT INTO account_command_submissions"),
                any(Object[].class))).thenAnswer(invocation -> {
                    insertedIdentityHash.set(invocation.getArgument(7));
                    return 0;
                });
        when(jdbcTemplate.query(
                contains("FROM account_command_submissions"), any(RowMapper.class), eq(command.commandId())))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("product_line")).thenReturn(command.productLine().name());
                    when(rs.getLong("user_id")).thenReturn(command.userId());
                    when(rs.getString("command_type")).thenReturn(command.commandType().name());
                    when(rs.getString("source")).thenReturn(command.source());
                    when(rs.getString("source_reference")).thenReturn(command.sourceReference());
                    when(rs.getString("identity_sha256")).thenReturn(insertedIdentityHash.get());
                    return List.of(mapper.mapRow(rs, 0));
                });

        assertThat(repository.register(command, "{\"commandId\":\"balance:deposit-1\"}", NOW)).isFalse();
    }

    @Test
    void reusedIdempotencyKeyWithDifferentPayloadIsRejected() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AccountCommandSubmissionRepository repository =
                new AccountCommandSubmissionRepository(jdbcTemplate);
        AccountUserCommand command = command("{\"amountUnits\":200}");
        when(jdbcTemplate.update(contains("INSERT INTO account_command_submissions"),
                any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.query(
                contains("FROM account_command_submissions"), any(RowMapper.class), eq(command.commandId())))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("product_line")).thenReturn(command.productLine().name());
                    when(rs.getLong("user_id")).thenReturn(command.userId());
                    when(rs.getString("command_type")).thenReturn(command.commandType().name());
                    when(rs.getString("source")).thenReturn(command.source());
                    when(rs.getString("source_reference")).thenReturn(command.sourceReference());
                    when(rs.getString("identity_sha256")).thenReturn("hash-of-original-payload");
                    return List.of(mapper.mapRow(rs, 0));
                });

        assertThatThrownBy(() -> repository.register(
                command, "{\"commandId\":\"balance:deposit-1\"}", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idempotency key was already used");
    }

    private AccountUserCommand command(String payload) {
        return new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                "balance:deposit-1",
                ProductLine.LINEAR_PERPETUAL,
                1001L,
                AccountUserCommandType.BALANCE_ADJUST,
                "ACCOUNT_HTTP",
                "deposit-1",
                null,
                payload,
                NOW,
                "trace-1");
    }
}
