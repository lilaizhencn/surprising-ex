package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionResponse;
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
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate, sequenceRepository,
                new ObjectMapper(), properties);
        when(sequenceRepository.nextSequence("position-event")).thenReturn(101L);
        when(sequenceRepository.nextSequence("account-outbox")).thenReturn(201L);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        repository.enqueuePositionUpdated("surprising.linear-delivery.account.position.events.v1", 9201L,
                position(), Instant.parse("2026-07-01T00:00:00Z"), "trace-1");

        verify(sequenceRepository).nextSequence("position-event");
        verify(sequenceRepository).nextSequence("account-outbox");
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
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(100))).thenReturn(List.of());

        repository.lockPending(100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(100));
        assertThat(sql.getValue())
                .contains("FROM account_outbox_events")
                .contains("published_at IS NULL")
                .doesNotContain("topic IN (?, ?)");
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
                eq("surprising.linear-delivery.account.position.events.v1"),
                eq("surprising.linear-delivery.account.liquidation-fee.events.v1"),
                eq(100))).thenReturn(List.of());

        repository.lockPending(100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq("surprising.linear-delivery.account.position.events.v1"),
                eq("surprising.linear-delivery.account.liquidation-fee.events.v1"),
                eq(100));
        assertThat(sql.getValue())
                .contains("topic IN (?, ?)")
                .contains("next_attempt_at <= now()");
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
