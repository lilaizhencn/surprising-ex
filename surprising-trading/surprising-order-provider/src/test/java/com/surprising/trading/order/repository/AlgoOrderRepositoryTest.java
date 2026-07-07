package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.AlgoOrderType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AlgoOrderRepositoryTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void openOrdersFiltersByContractTypeWhenProvided() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AlgoOrderRepository repository = new AlgoOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.openOrders(1001L, "BTC-USDT", 50, "VANILLA_OPTION");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("FROM instrument_current_versions c")
                .contains("i.symbol = c.symbol AND i.version = c.version")
                .contains("i.contract_type = ?");
        assertThat(args.getValue()).containsExactly(1001L, "BTC-USDT", "BTC-USDT",
                "VANILLA_OPTION", "VANILLA_OPTION", 50);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void cancelableOpenOrdersFiltersByContractTypeWhenProvided() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AlgoOrderRepository repository = new AlgoOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.cancelableOpenOrders(1001L, "BTC-USDT", AlgoOrderType.TWAP, 25, "LINEAR_DELIVERY");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("FROM instrument_current_versions c")
                .contains("i.symbol = c.symbol AND i.version = c.version")
                .contains("i.contract_type = ?")
                .contains("FOR UPDATE SKIP LOCKED");
        assertThat(args.getValue()).containsExactly(1001L, "BTC-USDT", "BTC-USDT",
                "TWAP", "TWAP", "LINEAR_DELIVERY", "LINEAR_DELIVERY", 25);
    }
}
