package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AccountOutboxRepositoryTest {

    @Test
    void claimPendingLeasesDuePrefixesPerTopicKey() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountProperties properties = new AccountProperties();
        properties.getOutbox().setMaxInFlight(3);
        properties.getOutbox().setMaxRowsPerKey(25);
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, properties);
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
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class))).thenReturn(List.of());

        repository.claimPending(100, Instant.parse("2026-07-01T00:00:30Z"),
                Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue())
                .contains("e.product_line = ?")
                .contains("e.topic IN (?, ?, ?, ?, ?, ?)");
        assertThat(args.getValue()).hasSize(15);
        assertThat(args.getValue()[1]).isEqualTo("OPTION");
        assertThat(args.getValue()[2]).isEqualTo(properties.getKafka().getPositionEventsTopic());
        assertThat(args.getValue()[3]).isEqualTo(properties.getKafka().getOpenInterestEventsTopic());
        assertThat(args.getValue()[4]).isEqualTo(properties.getKafka().getLiquidationFeeEventsTopic());
        assertThat(args.getValue()[5]).isEqualTo(properties.getKafka().getRiskWalletEventsTopic());
        assertThat(args.getValue()[6]).isEqualTo(properties.getKafka().getCommandResultsTopic());
        assertThat(args.getValue()[7]).isEqualTo(properties.getKafka().getUserCommandsTopic());
        assertThat(args.getValue()[9]).isEqualTo(100);
        assertThat(args.getValue()[10]).isEqualTo(32);
        assertThat(args.getValue()[12]).isEqualTo(100);
    }

    @Test
    void markPublishedBatchAcceptsSuccessfulRows() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate);
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
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        assertThatThrownBy(() -> repository.markPublished(List.of(901L, 902L),
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected=2 actual=1");
    }

    @Test
    void deletesOnlyPublishedAccountRowsInLockedBatches() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate);
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

}
