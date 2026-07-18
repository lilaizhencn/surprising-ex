package com.surprising.funding.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.funding.provider.config.FundingProperties;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class FundingRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void completePaymentCastsConditionalTerminalTimestamps() throws Exception {
        FundingRepository repository = new FundingRepository(jdbcTemplate, new FundingProperties());
        when(jdbcTemplate.query(contains("FROM funding_payments"), any(RowMapper.class), eq("funding-command")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getLong("payment_id")).thenReturn(11L);
                    when(resultSet.getLong("settlement_id")).thenReturn(22L);
                    when(resultSet.getLong("user_id")).thenReturn(33L);
                    when(resultSet.getString("status")).thenReturn("PENDING");
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        when(jdbcTemplate.update(contains("UPDATE funding_payments"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE funding_settlements"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("FROM funding_settlements"),
                eq(Long.class), eq(22L))).thenReturn(22L);

        boolean changed = repository.completePayment("funding-command", 33L, "APPLIED",
                null, null, Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(changed).isTrue();
        verify(jdbcTemplate).queryForObject(contains("FOR UPDATE"), eq(Long.class), eq(22L));
        verify(jdbcTemplate).update(contains(
                "applied_at = CASE WHEN ? THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END"),
                any(Object[].class));
        verify(jdbcTemplate).update(contains(
                "rejected_at = CASE WHEN ? THEN NULL ELSE CAST(? AS TIMESTAMPTZ) END"),
                any(Object[].class));
    }
}
