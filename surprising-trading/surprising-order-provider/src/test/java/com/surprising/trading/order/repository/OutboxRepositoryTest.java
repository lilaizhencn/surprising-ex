package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OutboxRepositoryTest {

    @Test
    void lockPendingSelectsUnpublishedDueRowsForRetry() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OutboxRepository repository = new OutboxRepository(jdbcTemplate, org.mockito.Mockito.mock(OrderRepository.class));
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(100))).thenReturn(List.of());

        repository.lockPending(100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(100));
        assertThat(sql.getValue())
                .contains("DISTINCT ON (topic, event_key)")
                .contains("published_at IS NULL")
                .contains("aggregate_type = 'ORDER'")
                .contains("next_attempt_at <= now()")
                .contains("pg_try_advisory_xact_lock")
                .contains("FOR UPDATE OF e SKIP LOCKED");
    }

    @Test
    void lockPendingFiltersByProductTopicsWhenProductTopicsAreEnabled() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);
        OutboxRepository repository = new OutboxRepository(jdbcTemplate,
                org.mockito.Mockito.mock(OrderRepository.class), properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(),
                eq("surprising.inverse-perp.order.events.v1"),
                eq("surprising.inverse-perp.order.commands.v1"),
                eq(100))).thenReturn(List.of());

        repository.lockPending(100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq("surprising.inverse-perp.order.events.v1"),
                eq("surprising.inverse-perp.order.commands.v1"),
                eq(100));
        assertThat(sql.getValue())
                .contains("e.topic IN (?, ?)")
                .contains("aggregate_type = 'ORDER'");
    }

    @Test
    void claimPendingLeasesUnpublishedDueRowsForPublishOutsideTransaction() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OutboxRepository repository = new OutboxRepository(jdbcTemplate, org.mockito.Mockito.mock(OrderRepository.class));
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Timestamp.class), eq(100),
                any(Timestamp.class), any(Timestamp.class))).thenReturn(List.of());

        repository.claimPending(100, Instant.parse("2026-07-01T00:00:30Z"),
                Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), any(Timestamp.class), eq(100),
                any(Timestamp.class), any(Timestamp.class));
        assertThat(sql.getValue())
                .contains("DISTINCT ON (topic, event_key)")
                .contains("UPDATE trading_outbox_events")
                .contains("SET next_attempt_at = ?")
                .contains("RETURNING e.id")
                .contains("FOR UPDATE OF e SKIP LOCKED");
    }

    @Test
    void claimPendingFiltersByProductTopicsWhenProductTopicsAreEnabled() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        OutboxRepository repository = new OutboxRepository(jdbcTemplate,
                org.mockito.Mockito.mock(OrderRepository.class), properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(),
                eq("surprising.option.order.events.v1"),
                eq("surprising.option.order.commands.v1"),
                any(Timestamp.class), eq(100), any(Timestamp.class), any(Timestamp.class)))
                .thenReturn(List.of());

        repository.claimPending(100, Instant.parse("2026-07-01T00:00:30Z"),
                Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq("surprising.option.order.events.v1"),
                eq("surprising.option.order.commands.v1"),
                any(Timestamp.class), eq(100), any(Timestamp.class), any(Timestamp.class));
        assertThat(sql.getValue())
                .contains("e.topic IN (?, ?)")
                .contains("UPDATE trading_outbox_events")
                .contains("RETURNING e.id");
    }

    @Test
    void markFailedSchedulesRetryWithBackoffAndTruncatesError() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OutboxRepository repository = new OutboxRepository(jdbcTemplate, org.mockito.Mockito.mock(OrderRepository.class));
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);
        String error = "x".repeat(1100);

        repository.markFailed(901L, error, Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("attempts = attempts + 1")
                .contains("last_error = ?")
                .contains("power(2, LEAST(attempts, 6))")
                .contains("next_attempt_at");
        assertThat((String) args.getValue()[0]).hasSize(1000);
        assertThat(args.getValue()[3]).isEqualTo(901L);
    }

    @Test
    void markFailedFailsFastWhenNoRowIsUpdated() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OutboxRepository repository = new OutboxRepository(jdbcTemplate, org.mockito.Mockito.mock(OrderRepository.class));
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> repository.markFailed(901L, "kafka unavailable",
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trading outbox event failed");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
