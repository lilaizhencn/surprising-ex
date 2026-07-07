package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AlgoOrderStatus;
import com.surprising.trading.api.model.AlgoOrderType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.model.AlgoOrderRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AlgoOrderRepositoryTest {

    @Test
    void insertScopesClientAlgoOrderIdByProductLine() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AlgoOrderRepository repository = new AlgoOrderRepository(jdbcTemplate);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(0);

        boolean inserted = repository.insert(algoOrder(ProductLine.OPTION));

        assertThat(inserted).isFalse();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("ON CONFLICT (product_line, user_id, client_algo_order_id) WHERE client_algo_order_id IS NOT NULL DO NOTHING");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void findByClientAlgoOrderIdScopesByProductLine() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AlgoOrderRepository repository = new AlgoOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.findByClientAlgoOrderId(ProductLine.INVERSE_DELIVERY, 1001L, "algo-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("WHERE product_line = ? AND user_id = ? AND client_algo_order_id = ?");
        assertThat(args.getValue()).containsExactly("INVERSE_DELIVERY", 1001L, "algo-1");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void dueOrdersScopesByProductLine() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AlgoOrderRepository repository = new AlgoOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        repository.dueOrders(ProductLine.OPTION, now, 25);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("WHERE product_line = ?")
                .contains("FOR UPDATE SKIP LOCKED");
        assertThat(args.getValue()).containsExactly("OPTION", java.sql.Timestamp.from(now), 25);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void openOrdersFiltersByProductLineWhenContractTypeProvided() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AlgoOrderRepository repository = new AlgoOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.openOrders(1001L, "BTC-USDT", 50, "VANILLA_OPTION");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .doesNotContain("FROM instrument_current_versions c")
                .doesNotContain("JOIN instruments");
        assertThat(args.getValue()).containsExactly(1001L, "BTC-USDT", "BTC-USDT",
                "OPTION", "OPTION", 50);
    }

    @Test
    void algoOrderMatchesContractTypeUsesOrderProductLine() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AlgoOrderRepository repository = new AlgoOrderRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class), any(), any()))
                .thenReturn(true);

        boolean matched = repository.algoOrderMatchesContractType(77L, "COIN_PERPETUAL");

        assertThat(matched).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> productLine = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), any(Class.class), eq(77L), productLine.capture());
        assertThat(sql.getValue())
                .contains("FROM trading_algo_orders")
                .contains("product_line = ?")
                .doesNotContain("JOIN instruments");
        assertThat(productLine.getValue()).isEqualTo("INVERSE_PERPETUAL");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void cancelableOpenOrdersFiltersByProductLineWhenContractTypeProvided() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AlgoOrderRepository repository = new AlgoOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.cancelableOpenOrders(1001L, "BTC-USDT", AlgoOrderType.TWAP, 25, "LINEAR_DELIVERY");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .doesNotContain("FROM instrument_current_versions c")
                .doesNotContain("JOIN instruments")
                .contains("FOR UPDATE SKIP LOCKED");
        assertThat(args.getValue()).containsExactly(1001L, "BTC-USDT", "BTC-USDT",
                "TWAP", "TWAP", "LINEAR_DELIVERY", "LINEAR_DELIVERY", 25);
    }

    private AlgoOrderRecord algoOrder(ProductLine productLine) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new AlgoOrderRecord(77L, productLine, 1001L, "algo-1", "BTC-USDT",
                AlgoOrderType.TWAP, OrderSide.BUY, 0L, 100L, 10L, 60L, 600L, MarginMode.CROSS,
                PositionSide.NET, false, false, TimeInForce.IOC, AlgoOrderStatus.PENDING,
                null, null, "trace-1", now, now, null, now, now);
    }
}
