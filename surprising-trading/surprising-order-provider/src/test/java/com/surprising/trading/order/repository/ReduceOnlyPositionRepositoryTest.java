package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class ReduceOnlyPositionRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void lockedOpenReduceOnlyStepsFailsOnOverflow() {
        ReduceOnlyPositionRepository repository = new ReduceOnlyPositionRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("FROM trading_orders"), anyRowMapper(),
                eq(1001L), eq("BTC-USDT"), eq("CROSS"), eq("NET"), eq(7L), eq("SELL")))
                .thenReturn(List.of(Long.MAX_VALUE, 1L));

        assertThatThrownBy(() -> repository.lockedOpenReduceOnlySteps(1001L, "BTC-USDT",
                MarginMode.CROSS, 7L, OrderSide.SELL))
                .isInstanceOf(ArithmeticException.class);
    }

    @SuppressWarnings("unchecked")
    private <T> RowMapper<T> anyRowMapper() {
        return any(RowMapper.class);
    }
}
