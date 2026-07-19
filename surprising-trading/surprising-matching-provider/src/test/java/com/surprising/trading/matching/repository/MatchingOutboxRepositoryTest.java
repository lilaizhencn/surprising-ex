package com.surprising.trading.matching.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.repository.MatchingOutboxRepository.MatchingOutboxWrite;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class MatchingOutboxRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void enqueueAllowsCurrentProductTopicsWhenProductTopicsAreEnabled() {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(contains("trading_outbox_seq"), any(RowMapper.class), eq(1)))
                .thenReturn(List.of(901L));
        when(jdbcTemplate.batchUpdate(
                contains("INSERT INTO trading_outbox_events"),
                any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1});

        repository.enqueue("MATCH_RESULT", 11L, "surprising.inverse-delivery.match.results.v1",
                "BTC-USD-260925", "PLACE", "{}", Instant.parse("2026-07-01T00:00:00Z"));

        verify(jdbcTemplate).batchUpdate(
                contains("INSERT INTO trading_outbox_events"),
                any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class));
    }

    @Test
    void enqueueBatchAllocatesOrderedIdsAndUsesOneJdbcBatch() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(contains("trading_outbox_seq"), any(RowMapper.class), eq(2)))
                .thenReturn(List.of(901L, 902L));
        when(jdbcTemplate.batchUpdate(
                contains("INSERT INTO trading_outbox_events"),
                any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1, 1});

        repository.enqueueBatch(List.of(
                new MatchingOutboxWrite("ACCOUNT_COMMAND", 11L, "topic", "1001", "TRADE", "{}", now),
                new MatchingOutboxWrite("MATCH_RESULT", 12L, "topic", "BTC-USDT", "PLACE", "{}", now)));

        ArgumentCaptor<String> sequenceSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sequenceSql.capture(), any(RowMapper.class), eq(2));
        assertThat(sequenceSql.getValue()).contains("ORDER BY n");
        ArgumentCaptor<org.springframework.jdbc.core.BatchPreparedStatementSetter> setter =
                ArgumentCaptor.forClass(org.springframework.jdbc.core.BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO trading_outbox_events"), setter.capture());
        assertThat(setter.getValue().getBatchSize()).isEqualTo(2);
    }

    @Test
    void enqueueRejectsOtherProductTopicBeforeWritingWhenProductTopicsAreEnabled() {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate, properties);

        assertThatThrownBy(() -> repository.enqueue("MATCH_RESULT", 11L,
                "surprising.linear-delivery.match.results.v1", "BTC-USDT-260925-70000-C", "PLACE", "{}",
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matching outbox topic must match current product line")
                .hasMessageContaining("surprising.option.match.results.v1");

        verify(jdbcTemplate, never()).batchUpdate(
                any(String.class), any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class));
    }

    @Test
    void enqueueFailsWhenOutboxInsertIsSkipped() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("trading_outbox_seq"), any(RowMapper.class), eq(1)))
                .thenReturn(List.of(901L));
        when(jdbcTemplate.batchUpdate(
                contains("INSERT INTO trading_outbox_events"),
                any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{0});

        assertThatThrownBy(() -> repository.enqueue("MATCH_RESULT", 11L, "topic", "BTC-USDT",
                "PLACE", "{}", Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matching outbox");
    }

    @Test
    void enqueueRejectsOrderBookDepthBecauseItUsesTheLatestOnlyPublisher() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate);

        assertThatThrownBy(() -> repository.enqueue("ORDER_BOOK_DEPTH", 11L,
                "surprising.perp.orderbook.depth.v1", "BTC-USDT", "SNAPSHOT", "{}",
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dedicated in-memory publisher");

        verify(jdbcTemplate, never()).batchUpdate(
                any(String.class), any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class));
    }

    @Test
    void enqueueRejectsPublicTradeBecauseFinancialOutboxMustStayIsolated() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate);

        assertThatThrownBy(() -> repository.enqueue("MATCH_TRADE", 11L,
                "surprising.perp.match.trades.v1", "BTC-USDT", "TRADE", "{}",
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dedicated in-memory publisher");

        verify(jdbcTemplate, never()).batchUpdate(
                any(String.class), any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class));
    }

    @Test
    void markPublishedFailsWhenNoRowIsUpdated() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate);
        when(jdbcTemplate.update(contains("SET published_at"), any(), any(), eq(901L)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.markPublished(901L, Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matching outbox publish");
    }

    @Test
    void markPublishedBatchUsesOneSqlUpdate() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate);
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
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        assertThatThrownBy(() -> repository.markPublished(List.of(901L, 902L),
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected=2 actual=1");
    }

    @Test
    void claimPendingLeasesUnpublishedDueRowsForPublishOutsideTransaction() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(32),
                any(Timestamp.class), any(Timestamp.class), eq(100),
                any(Timestamp.class), any(Timestamp.class))).thenReturn(List.of());

        repository.claimPendingBatches(100, Instant.parse("2026-07-01T00:00:30Z"),
                Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(32),
                any(Timestamp.class), any(Timestamp.class), eq(100),
                any(Timestamp.class), any(Timestamp.class));
        assertThat(sql.getValue())
                .contains("pending_ranked AS MATERIALIZED")
                .contains("first_value(event.next_attempt_at)")
                .contains("head_next_attempt_at <= ?")
                .contains("candidates AS MATERIALIZED")
                .contains("PARTITION BY event.aggregate_type, event.topic, event.event_key")
                .contains("next_attempt_at <= ?")
                .contains("event.xmin::text::bigint AS row_version")
                .contains("LIMIT ?")
                .contains("UPDATE trading_outbox_events")
                .contains("SET next_attempt_at = ?")
                .contains("RETURNING e.id")
                .contains("(e.id, e.xmin::text::bigint) IN");
    }

    @Test
    void claimPendingFiltersByProductTopicsWhenProductTopicsAreEnabled() {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(),
                eq("surprising.option.match.results.v1"),
                eq("surprising.option.account.user.commands.v1"),
                eq(32), any(Timestamp.class), any(Timestamp.class), eq(100),
                any(Timestamp.class), any(Timestamp.class)))
                .thenReturn(List.of());

        repository.claimPendingBatches(100, Instant.parse("2026-07-01T00:00:30Z"),
                Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq("surprising.option.match.results.v1"),
                eq("surprising.option.account.user.commands.v1"),
                eq(32), any(Timestamp.class), any(Timestamp.class), eq(100),
                any(Timestamp.class), any(Timestamp.class));
        assertThat(sql.getValue())
                .contains("event.topic IN (?, ?)")
                .contains("UPDATE trading_outbox_events")
                .contains("RETURNING e.id");
    }

    @Test
    void markFailedSchedulesRetryWithBackoffAndTruncatesError() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate);
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
    void releasePendingBatchClearsOnlyLeases() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(2);

        repository.releasePending(List.of(902L, 903L), Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("SET next_attempt_at = ?")
                .contains("WHERE published_at IS NULL")
                .contains("id IN (?, ?)")
                .doesNotContain("attempts = attempts + 1");
    }

    @Test
    void markFailedFailsWhenNoRowIsUpdated() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate);
        when(jdbcTemplate.update(contains("SET attempts = attempts + 1"), any(), any(), any(), eq(901L)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.markFailed(901L, "kafka unavailable",
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matching outbox failure mark");
    }

    @Test
    void deletesOnlyPublishedMatchingRowsInLockedBatches() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(7);

        assertThat(repository.deletePublishedBefore(Instant.parse("2026-07-01T00:00:00Z"), 100)).isEqualTo(7);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("aggregate_type IN")
                .contains("'MATCH_RESULT', 'ACCOUNT_COMMAND'")
                .doesNotContain("MATCH_TRADE")
                .doesNotContain("ORDER_BOOK_DEPTH")
                .contains("published_at < ?")
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("DELETE FROM trading_outbox_events");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
