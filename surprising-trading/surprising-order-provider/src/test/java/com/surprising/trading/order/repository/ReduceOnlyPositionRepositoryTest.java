package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("BTC-USDT"), eq("CROSS"), eq("NET"), eq(7L),
                eq("SELL")))
                .thenReturn(List.of(Long.MAX_VALUE, 1L));

        assertThatThrownBy(() -> repository.lockedOpenReduceOnlySteps(1001L, "BTC-USDT",
                MarginMode.CROSS, 7L, OrderSide.SELL))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void lockedPositionScopesByProductLine() {
        ReduceOnlyPositionRepository repository = new ReduceOnlyPositionRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.lockedPosition(ProductLine.OPTION, 1001L, "BTC-USDT-260925-70000-C",
                MarginMode.CROSS, PositionSide.NET);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .contains("FOR UPDATE");
        assertThat(args.getValue()).containsExactly("OPTION", 1001L, "BTC-USDT-260925-70000-C",
                "CROSS", "NET");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void lockedOpenReduceOnlyStepsScopesByProductLine() {
        ReduceOnlyPositionRepository repository = new ReduceOnlyPositionRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(4L));

        long steps = repository.lockedOpenReduceOnlySteps(ProductLine.LINEAR_DELIVERY, 1001L,
                "BTC-USDT-260925", MarginMode.ISOLATED, 7L, PositionSide.NET, OrderSide.SELL);

        assertThat(steps).isEqualTo(4L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .contains("FOR UPDATE");
        assertThat(args.getValue()).containsExactly("LINEAR_DELIVERY", 1001L, "BTC-USDT-260925",
                "ISOLATED", "NET", 7L, "SELL");
    }

    @SuppressWarnings("unchecked")
    private <T> RowMapper<T> anyRowMapper() {
        return any(RowMapper.class);
    }
}
