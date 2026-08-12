package com.surprising.funding.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class FundingPaymentCompletionRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void completePaymentUsesConditionalBatchUpdateAndIncrementalSettlementCounters() throws Exception {
        FundingPaymentCompletionRepository repository =
                new FundingPaymentCompletionRepository(jdbcTemplate);
        stubPaymentState("PENDING");
        when(jdbcTemplate.queryForObject(contains("WITH input"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        boolean changed = repository.complete("funding-command", 33L, "APPLIED",
                null, null, Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(changed).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Integer.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("UPDATE funding_payments")
                .contains("UPDATE funding_settlements")
                .contains("applied_payment_count = s.applied_payment_count + c.applied_count")
                .contains("rejected_payment_count = s.rejected_payment_count + c.rejected_count")
                .doesNotContain("GROUP BY settlement_id\n                  ) counts");
    }

    @Test
    void duplicatePaymentResultIsIdempotentWithoutUpdatingCounters() throws Exception {
        FundingPaymentCompletionRepository repository =
                new FundingPaymentCompletionRepository(jdbcTemplate);
        stubPaymentState("APPLIED");

        boolean changed = repository.complete("funding-command", 33L, "APPLIED",
                null, null, Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(changed).isFalse();
        verify(jdbcTemplate, never()).queryForObject(contains("WITH input"), eq(Integer.class),
                any(Object[].class));
    }

    @Test
    void conflictingTerminalResultFailsClosed() throws Exception {
        FundingPaymentCompletionRepository repository =
                new FundingPaymentCompletionRepository(jdbcTemplate);
        stubPaymentState("REJECTED");

        assertThatThrownBy(() -> repository.complete("funding-command", 33L, "APPLIED",
                null, null, Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting funding payment result");
    }

    private void stubPaymentState(String status) throws Exception {
        when(jdbcTemplate.query(contains("WHERE command_id IN"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getLong("payment_id")).thenReturn(11L);
                    when(resultSet.getLong("settlement_id")).thenReturn(22L);
                    when(resultSet.getString("command_id")).thenReturn("funding-command");
                    when(resultSet.getLong("user_id")).thenReturn(33L);
                    when(resultSet.getString("status")).thenReturn(status);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
    }
}
