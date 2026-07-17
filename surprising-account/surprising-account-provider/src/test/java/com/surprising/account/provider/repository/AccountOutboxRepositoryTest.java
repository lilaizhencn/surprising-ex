package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
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
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, sequenceRepository,
                new ObjectMapper(), properties);
        when(sequenceRepository.nextSequence("position-event")).thenReturn(101L);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        repository.enqueuePositionUpdated("surprising.linear-delivery.account.position.events.v1", 9201L,
                position(), Instant.parse("2026-07-01T00:00:00Z"), "trace-1");

        verify(sequenceRepository).nextSequence("position-event");
        verify(sequenceRepository, never()).nextSequence("account-outbox");
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

        verify(sequenceRepository, never()).nextSequence("position-event");
        verify(sequenceRepository, never()).nextSequence("account-outbox");
        verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void lockPendingLeavesLegacyTopicsUnfiltered() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, null, new ObjectMapper());
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq("LINEAR_PERPETUAL"), eq(100)))
                .thenReturn(List.of());

        repository.lockPending(100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq("LINEAR_PERPETUAL"), eq(100));
        assertThat(sql.getValue())
                .contains("FROM account_outbox_events")
                .contains("published_at IS NULL")
                .contains("product_line = ?")
                .doesNotContain("topic IN (");
    }

    @Test
    void lockPendingFiltersByProductTopicsWhenProductTopicsAreEnabled() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, null, new ObjectMapper(),
                properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(),
                eq("LINEAR_DELIVERY"),
                eq("surprising.linear-delivery.account.position.events.v1"),
                eq("surprising.linear-delivery.account.liquidation-fee.events.v1"),
                eq("surprising.linear-delivery.account.position-cache.events.v1"),
                eq(100))).thenReturn(List.of());

        repository.lockPending(100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq("LINEAR_DELIVERY"),
                eq("surprising.linear-delivery.account.position.events.v1"),
                eq("surprising.linear-delivery.account.liquidation-fee.events.v1"),
                eq("surprising.linear-delivery.account.position-cache.events.v1"),
                eq(100));
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .contains("topic IN (?, ?, ?)")
                .contains("next_attempt_at <= now()");
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
                .contains("SELECT DISTINCT ON (topic, event_key)")
                .contains("pg_try_advisory_xact_lock(hashtext(topic), hashtext(event_key))")
                .contains("CROSS JOIN LATERAL")
                .contains("row_number() OVER (ORDER BY prefix.id) AS key_rank")
                .contains("bool_or(prefix.next_attempt_at > ?)")
                .contains("ORDER BY due_prefix.key_rank, k.first_id, due_prefix.id")
                .contains("UPDATE account_outbox_events e")
                .contains("RETURNING e.id, e.topic, e.event_key");
        assertThat(args.getValue()[0]).isEqualTo("LINEAR_PERPETUAL");
        assertThat(args.getValue()[2]).isEqualTo(12);
        assertThat(args.getValue()[4]).isEqualTo("LINEAR_PERPETUAL");
        assertThat(args.getValue()[5]).isEqualTo(25);
        assertThat(args.getValue()[7]).isEqualTo(100);
    }

    @Test
    void markPublishedBatchAcceptsSuccessfulRows() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, null, new ObjectMapper());
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
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, null, new ObjectMapper());
        when(jdbcTemplate.batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[] {1, 0});

        assertThatThrownBy(() -> repository.markPublished(List.of(901L, 902L),
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account outbox event published 902");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }

    private PositionResponse position() {
        return new PositionResponse(1001L, "BTC-USDT-260925", 7L, MarginMode.CROSS, PositionSide.NET,
                10L, 60_000L, 0L, Instant.parse("2026-07-01T00:00:00Z"));
    }
}
