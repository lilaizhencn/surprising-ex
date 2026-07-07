package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.CancelAllAfterTimer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CancelAllAfterRepositoryTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void upsertScopesTimerByProductLine() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        CancelAllAfterRepository repository = new CancelAllAfterRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        Instant triggerAt = Instant.parse("2026-07-01T00:00:01Z");
        when(jdbcTemplate.queryForObject(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(new CancelAllAfterTimer(1001L, "BTC-USDT", 1000L, "ACTIVE", triggerAt, now, 0, 0));

        repository.upsert(ProductLine.OPTION, 1001L, "BTC-USDT", 1000L, triggerAt, "ACTIVE", now, "trace-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("product_line, user_id, symbol_scope")
                .contains("ON CONFLICT (product_line, user_id, symbol_scope)");
        assertThat(args.getValue()[0]).isEqualTo("OPTION");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void claimDueTimersFiltersAndUpdatesByProductLine() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        CancelAllAfterRepository repository = new CancelAllAfterRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.claimDueTimers(ProductLine.LINEAR_DELIVERY, now, 100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("WHERE product_line = ?")
                .contains("timer.product_line = due.product_line");
        assertThat(args.getValue()).containsExactly("LINEAR_DELIVERY", Timestamp.from(now), 100, Timestamp.from(now));
    }

    @Test
    void markTriggeredScopesByProductLine() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        CancelAllAfterRepository repository = new CancelAllAfterRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        repository.markTriggered(ProductLine.OPTION, 1001L, "BTC-USDT", 2, 1, now);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("WHERE product_line = ?")
                .contains("AND user_id = ?")
                .contains("AND symbol_scope = ?");
    }
}
