package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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
    void enqueueAllowsCurrentProductTopicsWhenProductTopicsAreEnabled() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        OutboxRepository repository = new OutboxRepository(jdbcTemplate, orderRepository, properties);
        when(orderRepository.nextSequence("outbox")).thenReturn(901L);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        long outboxId = repository.enqueue("ORDER", 9001L,
                "surprising.linear-delivery.order.commands.v1", "BTC-USDT-260925", "PLACE", "{}",
                Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(outboxId).isEqualTo(901L);
        verify(orderRepository).nextSequence("outbox");
    }

    @Test
    void enqueueRejectsOtherProductTopicBeforeWritingWhenProductTopicsAreEnabled() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        OutboxRepository repository = new OutboxRepository(jdbcTemplate, orderRepository, properties);

        assertThatThrownBy(() -> repository.enqueue("ORDER", 9001L,
                "surprising.linear-delivery.order.commands.v1", "BTC-USDT-260925-70000-C", "PLACE", "{}",
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trading outbox topic must match current product line")
                .hasMessageContaining("surprising.option.order.commands.v1");

        verify(orderRepository, never()).nextSequence("outbox");
        verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
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
                eq("surprising.option.account.user.commands.v1"),
                any(Timestamp.class), eq(100), any(Timestamp.class), any(Timestamp.class)))
                .thenReturn(List.of());

        repository.claimPending(100, Instant.parse("2026-07-01T00:00:30Z"),
                Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq("surprising.option.order.events.v1"),
                eq("surprising.option.order.commands.v1"),
                eq("surprising.option.account.user.commands.v1"),
                any(Timestamp.class), eq(100), any(Timestamp.class), any(Timestamp.class));
        assertThat(sql.getValue())
                .contains("e.topic IN (?, ?, ?)")
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
    void markPublishedBatchUsesOneSqlUpdate() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OutboxRepository repository = new OutboxRepository(
                jdbcTemplate, org.mockito.Mockito.mock(OrderRepository.class));
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(2);

        repository.markPublished(List.of(901L, 902L), Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("SET published_at")
                .contains("WHERE published_at IS NULL")
                .contains("id IN (?, ?)");
        assertThat(args.getValue()).hasSize(4);
        assertThat(args.getValue()[2]).isEqualTo(901L);
        assertThat(args.getValue()[3]).isEqualTo(902L);
    }

    @Test
    void markPublishedBatchFailsWhenAnyRowIsSkipped() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OutboxRepository repository = new OutboxRepository(
                jdbcTemplate, org.mockito.Mockito.mock(OrderRepository.class));
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        assertThatThrownBy(() -> repository.markPublished(List.of(901L, 902L),
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected=2 actual=1");
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

    @Test
    void deletesOnlyPublishedTradingRowsInLockedBatches() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OutboxRepository repository = new OutboxRepository(jdbcTemplate, org.mockito.Mockito.mock(OrderRepository.class));
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(7);

        assertThat(repository.deletePublishedBefore(Instant.parse("2026-07-01T00:00:00Z"), 100)).isEqualTo(7);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("aggregate_type = 'ORDER'")
                .contains("published_at < ?")
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("DELETE FROM trading_outbox_events");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
