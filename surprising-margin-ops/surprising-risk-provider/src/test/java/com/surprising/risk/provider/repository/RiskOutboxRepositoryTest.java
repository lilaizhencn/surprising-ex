package com.surprising.risk.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class RiskOutboxRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private RiskSequenceRepository sequenceRepository;

    @Test
    void enqueueAllowsCurrentLiquidationTopicWhenProductTopicsAreEnabled() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        RiskOutboxRepository repository = new RiskOutboxRepository(jdbcTemplate, sequenceRepository, properties);
        when(sequenceRepository.nextSequence("risk-outbox")).thenReturn(901L);
        when(jdbcTemplate.update(contains("INSERT INTO risk_outbox_events"), any(Object[].class)))
                .thenReturn(1);

        repository.enqueue("surprising.linear-delivery.liquidation.candidates.v1",
                "BTC-USDT-260925", "LIQUIDATION_CANDIDATE", "{}", Instant.parse("2026-07-01T00:00:00Z"));

        verify(sequenceRepository).nextSequence("risk-outbox");
        verify(jdbcTemplate).update(contains("INSERT INTO risk_outbox_events"), any(Object[].class));
    }

    @Test
    void enqueueRejectsOtherProductTopicBeforeWritingWhenProductTopicsAreEnabled() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        RiskOutboxRepository repository = new RiskOutboxRepository(jdbcTemplate, sequenceRepository, properties);

        assertThatThrownBy(() -> repository.enqueue("surprising.linear-delivery.risk.position.events.v1",
                "BTC-USDT-260925-70000-C", "POSITION_RISK_UPDATED", "{}",
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("risk outbox topic must match current product line")
                .hasMessageContaining("surprising.option.liquidation.candidates.v1");

        verify(sequenceRepository, never()).nextSequence("risk-outbox");
        verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void enqueueFailsWhenOutboxInsertIsSkipped() {
        RiskOutboxRepository repository = new RiskOutboxRepository(jdbcTemplate, sequenceRepository);
        when(sequenceRepository.nextSequence("risk-outbox")).thenReturn(901L);
        when(jdbcTemplate.update(contains("INSERT INTO risk_outbox_events"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.enqueue("risk-topic", "BTC-USDT", "LIQUIDATION_CANDIDATE",
                "{}", Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("risk outbox enqueue");
    }

    @Test
    void markPublishedFailsWhenNoRowIsUpdated() {
        RiskOutboxRepository repository = new RiskOutboxRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.update(contains("SET published_at"), any(), any(), eq(901L)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.markPublished(901L, Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("risk outbox publish mark");
    }

    @Test
    void claimPendingLeasesDuePrefixesPerTopicKey() {
        RiskProperties properties = new RiskProperties();
        properties.getOutbox().setMaxInFlight(3);
        properties.getOutbox().setMaxRowsPerKey(25);
        RiskOutboxRepository repository = new RiskOutboxRepository(jdbcTemplate, sequenceRepository, properties);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        Instant leaseUntil = Instant.parse("2026-07-01T00:00:30Z");
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class))).thenReturn(List.of());

        repository.claimPending(100, leaseUntil, now);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue())
                .contains("SELECT DISTINCT ON (topic, event_key)")
                .contains("pg_try_advisory_xact_lock(hashtext(topic), hashtext(event_key))")
                .contains("CROSS JOIN LATERAL")
                .contains("row_number() OVER (ORDER BY prefix.id) AS key_rank")
                .contains("bool_or(prefix.next_attempt_at > ?)")
                .contains("ORDER BY due_prefix.key_rank, k.first_id, due_prefix.id")
                .contains("SET next_attempt_at = ?")
                .contains("RETURNING e.id, e.topic, e.event_key");
        assertThat(args.getValue()[1]).isEqualTo(12);
        assertThat(args.getValue()[3]).isEqualTo(25);
        assertThat(args.getValue()[5]).isEqualTo(100);
    }

    @Test
    void claimPendingFiltersByProductTopicsWhenProductTopicsAreEnabled() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        RiskOutboxRepository repository = new RiskOutboxRepository(jdbcTemplate, sequenceRepository, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class))).thenReturn(List.of());

        repository.claimPending(100, Instant.parse("2026-07-01T00:00:30Z"),
                Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("topic = ?")
                .contains("next_attempt_at <= ?");
    }

    @Test
    void markPublishedBatchAcceptsSuccessfulRows() {
        RiskOutboxRepository repository = new RiskOutboxRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[] {1, Statement.SUCCESS_NO_INFO});

        repository.markPublished(List.of(901L, 902L), Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<BatchPreparedStatementSetter> setter =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(contains("SET published_at"), setter.capture());
        assertThat(setter.getValue().getBatchSize()).isEqualTo(2);
    }

    @Test
    void markPublishedBatchFailsWhenAnyRowIsSkipped() {
        RiskOutboxRepository repository = new RiskOutboxRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[] {1, 0});

        assertThatThrownBy(() -> repository.markPublished(List.of(901L, 902L),
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("risk outbox event published 902");
    }

    @Test
    void markFailedSchedulesRetryWithBackoffAndTruncatesError() {
        RiskOutboxRepository repository = new RiskOutboxRepository(jdbcTemplate, sequenceRepository);
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
        RiskOutboxRepository repository = new RiskOutboxRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.update(contains("SET attempts = attempts + 1"), any(), any(), any(), eq(901L)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.markFailed(901L, "kafka unavailable",
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("risk outbox failure mark");
    }

    @Test
    void deletesOnlyPublishedRiskRowsInLockedBatches() {
        RiskOutboxRepository repository = new RiskOutboxRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(7);

        assertThat(repository.deletePublishedBefore(Instant.parse("2026-07-01T00:00:00Z"), 100)).isEqualTo(7);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("published_at < ?")
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("DELETE FROM risk_outbox_events");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
