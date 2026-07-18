package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SpotOrderReservationRepositoryTest {

    @Test
    void buyRequirementLocksQuoteNotionalPlusWorstPositiveFee() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SpotOrderReservationRepository repository = new SpotOrderReservationRepository(jdbcTemplate,
                mock(OrderRepository.class));
        when(jdbcTemplate.query(contains("i.instrument_type = 'SPOT'"), anyRowMapper(),
                isNull(), eq("BTC-USDT-SPOT"), eq(3L), eq("LIMIT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(spotRow(), 0));
                });

        var requirement = repository.requirement("BTC-USDT-SPOT", 3L, OrderSide.BUY, OrderType.LIMIT,
                65_000L, 10L, 10_000L, 5_000L, new OrderFeeSnapshot(200L, 500L, "INSTRUMENT"));

        assertThat(requirement).isPresent();
        assertThat(requirement.get().asset()).isEqualTo("USDT");
        assertThat(requirement.get().reservedUnits()).isEqualTo(6_503_250L);
    }

    @Test
    void sellRequirementLocksBaseQuantityOnly() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SpotOrderReservationRepository repository = new SpotOrderReservationRepository(jdbcTemplate,
                mock(OrderRepository.class));
        when(jdbcTemplate.query(contains("i.instrument_type = 'SPOT'"), anyRowMapper(),
                isNull(), eq("BTC-USDT-SPOT"), eq(3L), eq("LIMIT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(spotRow(), 0));
                });

        var requirement = repository.requirement("BTC-USDT-SPOT", 3L, OrderSide.SELL, OrderType.LIMIT,
                65_000L, 10L, 10_000L, 5_000L, new OrderFeeSnapshot(200L, 500L, "INSTRUMENT"));

        assertThat(requirement).isPresent();
        assertThat(requirement.get().asset()).isEqualTo("BTC");
        assertThat(requirement.get().reservedUnits()).isEqualTo(1_000_000L);
    }

    @Test
    void reserveReturnsFalseWhenAtomicSpotBalanceReservationDoesNotApply() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        SpotOrderReservationRepository repository = new SpotOrderReservationRepository(jdbcTemplate, orderRepository);
        when(jdbcTemplate.update(contains("UPDATE account_product_balances"), any(Object[].class)))
                .thenReturn(0);

        assertThat(repository.reserve(1001L, "USDT", 9002L, "BTC-USDT-SPOT",
                OrderSide.BUY, 6_503L, Instant.parse("2026-07-01T00:00:00Z"))).isFalse();
    }

    private ResultSet spotRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("base_asset")).thenReturn("BTC");
        when(rs.getString("quote_asset")).thenReturn("USDT");
        when(rs.getLong("quantity_step_units")).thenReturn(100_000L);
        when(rs.getLong("notional_multiplier_units")).thenReturn(10L);
        when(rs.wasNull()).thenReturn(true);
        return rs;
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
