package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class AccountOutboxRepositoryTest {

    @Test
    void enqueuePositionUpdatedAllowsCurrentProductTopicWhenProductTopicsAreEnabled() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountSequenceRepository sequenceRepository = org.mockito.Mockito.mock(AccountSequenceRepository.class);
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        PositionCacheProjectionRepository projectionRepository =
                org.mockito.Mockito.mock(PositionCacheProjectionRepository.class);
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, sequenceRepository,
                new ObjectMapper(), properties, projectionRepository);
        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.POSITION_EVENT)).thenReturn(101L);
        when(projectionRepository.captureFinalSnapshot(
                ProductLine.LINEAR_DELIVERY, 1001L, "BTC-USDT-260925", MarginMode.CROSS, PositionSide.NET))
                .thenReturn(snapshot(ProductLine.LINEAR_DELIVERY));
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        var event = repository.enqueuePositionUpdated("surprising.linear-delivery.account.position.events.v1", 9201L,
                position(), Instant.parse("2026-07-01T00:00:00Z"), "trace-1");

        verify(sequenceRepository).nextSequence(AccountSequenceRepository.Sequence.POSITION_EVENT);
        assertThat(event.partitionKey()).isEqualTo("LINEAR_DELIVERY:1001");
        assertThat(event.revision()).isEqualTo(77L);
        assertThat(event.entryValueTicks()).isEqualTo(600_000L);
        assertThat(event.marginUnits()).isEqualTo(20_000L);
    }

    @Test
    void enqueuePositionUpdatedRejectsOtherProductTopicBeforeWritingWhenProductTopicsAreEnabled() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountSequenceRepository sequenceRepository = org.mockito.Mockito.mock(AccountSequenceRepository.class);
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, sequenceRepository,
                new ObjectMapper(), properties);

        assertThatThrownBy(() -> repository.enqueuePositionUpdated(
                "surprising.linear-delivery.account.position.events.v1", 9201L, position(),
                Instant.parse("2026-07-01T00:00:00Z"), "trace-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account outbox topic must match current product line")
                .hasMessageContaining("surprising.option.account.position.events.v1");

        verifyNoInteractions(sequenceRepository);
        verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void claimPendingLeasesDuePrefixesPerTopicKey() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountProperties properties = new AccountProperties();
        properties.getOutbox().setMaxInFlight(3);
        properties.getOutbox().setMaxRowsPerKey(25);
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, null, new ObjectMapper(),
                properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class))).thenReturn(List.of());

        repository.claimPending(100, Instant.parse("2026-07-01T00:00:30Z"),
                Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue())
                .contains("pending AS MATERIALIZED")
                .contains("earliest AS MATERIALIZED")
                .contains("locked_keys AS MATERIALIZED")
                .contains("candidates AS MATERIALIZED")
                .contains("PARTITION BY e.topic, e.event_key")
                .contains("row_number() OVER key_order")
                .contains("bool_or(e.next_attempt_at > ?) OVER key_order")
                .contains("pg_try_advisory_xact_lock(hashtext(topic), hashtext(event_key))")
                .contains("JOIN pending p")
                .contains("p.key_rank <= ?")
                .contains("NOT p.blocked_by_retry")
                .contains("ORDER BY p.key_rank, k.first_id, p.id")
                .doesNotContain("CROSS JOIN LATERAL")
                .doesNotContain("DISTINCT ON")
                .contains("UPDATE account_outbox_events e")
                .contains("RETURNING e.id, e.topic, e.event_key");
        assertThat(args.getValue()).hasSize(9);
        assertThat(args.getValue()[1]).isEqualTo("LINEAR_PERPETUAL");
        assertThat(args.getValue()[3]).isEqualTo(12);
        assertThat(args.getValue()[4]).isEqualTo(25);
        assertThat(args.getValue()[6]).isEqualTo(100);
    }

    @Test
    void claimPendingScopesSinglePassToCurrentProductTopics() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, null, new ObjectMapper(),
                properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class))).thenReturn(List.of());

        repository.claimPending(100, Instant.parse("2026-07-01T00:00:30Z"),
                Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue())
                .contains("e.product_line = ?")
                .contains("e.topic IN (?, ?, ?, ?)");
        assertThat(args.getValue()).hasSize(13);
        assertThat(args.getValue()[1]).isEqualTo("OPTION");
        assertThat(args.getValue()[2]).isEqualTo(properties.getKafka().getPositionEventsTopic());
        assertThat(args.getValue()[3]).isEqualTo(properties.getKafka().getLiquidationFeeEventsTopic());
        assertThat(args.getValue()[4]).isEqualTo(properties.getKafka().getCommandResultsTopic());
        assertThat(args.getValue()[5]).isEqualTo(properties.getKafka().getUserCommandsTopic());
        assertThat(args.getValue()[7]).isEqualTo(100);
        assertThat(args.getValue()[8]).isEqualTo(32);
        assertThat(args.getValue()[10]).isEqualTo(100);
    }

    @Test
    void markPublishedBatchAcceptsSuccessfulRows() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, null, new ObjectMapper());
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
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, null, new ObjectMapper());
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        assertThatThrownBy(() -> repository.markPublished(List.of(901L, 902L),
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected=2 actual=1");
    }

    @Test
    void deletesOnlyPublishedAccountRowsInLockedBatches() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, null, new ObjectMapper());
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(7);

        assertThat(repository.deletePublishedBefore(Instant.parse("2026-07-01T00:00:00Z"), 100)).isEqualTo(7);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .contains("published_at < ?")
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("DELETE FROM account_outbox_events");
        assertThat(args.getValue()[0]).isEqualTo("LINEAR_PERPETUAL");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }

    private PositionResponse position() {
        return new PositionResponse(1001L, "BTC-USDT-260925", 7L, MarginMode.CROSS, PositionSide.NET,
                10L, 60_000L, 0L, Instant.parse("2026-07-01T00:00:00Z"));
    }

    private PositionCacheEvent snapshot(ProductLine productLine) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new PositionCacheEvent(77L, productLine, 1001L, "BTC-USDT-260925", 7L,
                MarginMode.CROSS, PositionSide.NET, 10L, 60_000L, 600_000L, 0L,
                "USDT", 20_000L, now, now, 77L);
    }
}
