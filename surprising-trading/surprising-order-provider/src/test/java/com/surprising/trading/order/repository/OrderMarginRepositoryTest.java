package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.MarginMode;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderMarginRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private OrderRepository orderRepository;

    @Test
    void reserveFailsFastWhenGuardedBalanceUpdateDoesNotApply() throws Exception {
        OrderMarginRepository repository = new OrderMarginRepository(jdbcTemplate, orderRepository);
        when(jdbcTemplate.query(contains("FROM account_balances"), anyRowMapper(), eq(1001L), eq("USDT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("available_units")).thenReturn(1_000L);
                    when(rs.getLong("locked_units")).thenReturn(0L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(orderRepository.nextSequence("margin-reservation")).thenReturn(77L);
        when(jdbcTemplate.update(contains("INSERT INTO account_margin_reservations"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_balances"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.reserve(1001L, "USDT", 9002L, "BTC-USDT",
                MarginMode.CROSS, 500L, Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserve order margin");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
