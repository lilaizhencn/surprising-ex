package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OrderMarkPriceRepositoryTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void readsFreshVersionedMarkPriceTicks() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OrderMarkPriceRepository repository = new OrderMarkPriceRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("price_mark_ticks"), any(RowMapper.class),
                eq(5_000L), eq("BTC-USDT"), eq(7L))).thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("mark_ticks")).thenReturn(65_001L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        OptionalLong markTicks = repository.latestMarkPriceTicks("BTC-USDT", 7L, 5_000L);

        assertThat(markTicks).hasValue(65_001L);
        verify(jdbcTemplate).query(contains("i.version = ?"), any(RowMapper.class),
                eq(5_000L), eq("BTC-USDT"), eq(7L));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void returnsEmptyWhenNoFreshPositiveMarkExists() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OrderMarkPriceRepository repository = new OrderMarkPriceRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), eq(1L), eq("BTC-USDT"), eq(7L)))
                .thenReturn(List.of());

        OptionalLong markTicks = repository.latestMarkPriceTicks("BTC-USDT", 7L, 0L);

        assertThat(markTicks).isEmpty();
    }
}
