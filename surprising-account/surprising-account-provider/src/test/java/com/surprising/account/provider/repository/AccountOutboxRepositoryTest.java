package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class AccountOutboxRepositoryTest {

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
}
