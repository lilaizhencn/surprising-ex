package com.surprising.trading.matching.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class MatchingMarginRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ResultSet resultSet;

    @Test
    void releaseAllUsesProductBalanceForDeliveryMarginAccount() throws Exception {
        MatchingMarginRepository repository = new MatchingMarginRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(), eq(9001L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    when(resultSet.getString("account_type")).thenReturn("USDT_DELIVERY");
                    when(resultSet.getLong("user_id")).thenReturn(1001L);
                    when(resultSet.getString("asset")).thenReturn("USDT");
                    when(resultSet.getLong("reserved_units")).thenReturn(600L);
                    when(resultSet.getLong("released_units")).thenReturn(100L);
                    when(resultSet.getLong("position_margin_units")).thenReturn(200L);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        when(jdbcTemplate.update(contains("UPDATE account_product_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_margin_reservations"), any(Object[].class)))
                .thenReturn(1);

        repository.releaseAll(9001L, "CANCEL", now);

        verify(jdbcTemplate).update(contains("UPDATE account_product_balances"),
                eq(300L), eq(300L), any(Timestamp.class), eq("USDT_DELIVERY"), eq(1001L), eq("USDT"), eq(300L));
        verify(jdbcTemplate).update(contains("UPDATE account_margin_reservations"),
                eq(300L), eq(300L), eq(300L), eq("CANCEL"), any(Timestamp.class), eq(9001L));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
