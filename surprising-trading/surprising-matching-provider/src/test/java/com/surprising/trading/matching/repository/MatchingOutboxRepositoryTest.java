package com.surprising.trading.matching.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.matching.config.MatchingProperties;
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

    @Mock
    private MatchingSequenceRepository sequenceRepository;

    @Test
    void enqueueFailsWhenOutboxInsertIsSkipped() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate, sequenceRepository);
        when(sequenceRepository.nextSequence("outbox")).thenReturn(901L);
        when(jdbcTemplate.update(contains("INSERT INTO trading_outbox_events"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.enqueue("MATCH_RESULT", 11L, "topic", "BTC-USDT",
                "PLACE", "{}", Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matching outbox");
    }

    @Test
    void markPublishedFailsWhenNoRowIsUpdated() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.update(contains("SET published_at"), any(), any(), eq(901L)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.markPublished(901L, Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matching outbox publish");
    }

    @Test
    void lockPendingSelectsUnpublishedDueRowsForRetry() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(100))).thenReturn(List.of());

        repository.lockPending(100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(100));
        assertThat(sql.getValue())
                .contains("DISTINCT ON (topic, event_key)")
                .contains("published_at IS NULL")
                .contains("aggregate_type IN ('MATCH_TRADE', 'MATCH_RESULT', 'ORDER_BOOK_DEPTH')")
                .contains("next_attempt_at <= now()")
                .contains("pg_try_advisory_xact_lock")
                .contains("FOR UPDATE OF e SKIP LOCKED");
    }

    @Test
    void lockPendingFiltersByProductTopicsWhenProductTopicsAreEnabled() {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate, sequenceRepository,
                properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(),
                eq("surprising.inverse-perp.match.results.v1"),
                eq("surprising.inverse-perp.match.trades.v1"),
                eq("surprising.inverse-perp.orderbook.depth.v1"),
                eq(100))).thenReturn(List.of());

        repository.lockPending(100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq("surprising.inverse-perp.match.results.v1"),
                eq("surprising.inverse-perp.match.trades.v1"),
                eq("surprising.inverse-perp.orderbook.depth.v1"),
                eq(100));
        assertThat(sql.getValue())
                .contains("e.topic IN (?, ?, ?)")
                .contains("aggregate_type IN ('MATCH_TRADE', 'MATCH_RESULT', 'ORDER_BOOK_DEPTH')");
    }

    @Test
    void claimPendingLeasesUnpublishedDueRowsForPublishOutsideTransaction() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate, sequenceRepository);
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
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate, sequenceRepository,
                properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(),
                eq("surprising.option.match.results.v1"),
                eq("surprising.option.match.trades.v1"),
                eq("surprising.option.orderbook.depth.v1"),
                any(Timestamp.class), eq(100), any(Timestamp.class), any(Timestamp.class)))
                .thenReturn(List.of());

        repository.claimPending(100, Instant.parse("2026-07-01T00:00:30Z"),
                Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq("surprising.option.match.results.v1"),
                eq("surprising.option.match.trades.v1"),
                eq("surprising.option.orderbook.depth.v1"),
                any(Timestamp.class), eq(100), any(Timestamp.class), any(Timestamp.class));
        assertThat(sql.getValue())
                .contains("e.topic IN (?, ?, ?)")
                .contains("UPDATE trading_outbox_events")
                .contains("RETURNING e.id");
    }

    @Test
    void markFailedSchedulesRetryWithBackoffAndTruncatesError() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate, sequenceRepository);
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
    void markFailedFailsWhenNoRowIsUpdated() {
        MatchingOutboxRepository repository = new MatchingOutboxRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.update(contains("SET attempts = attempts + 1"), any(), any(), any(), eq(901L)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.markFailed(901L, "kafka unavailable",
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matching outbox failure mark");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
