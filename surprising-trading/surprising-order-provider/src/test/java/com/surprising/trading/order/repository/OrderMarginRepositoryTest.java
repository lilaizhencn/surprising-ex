package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    void requirementScopesLeverageSettingsByInstrumentProductLine() {
        OrderMarginRepository repository = new OrderMarginRepository(jdbcTemplate, orderRepository);
        when(jdbcTemplate.query(contains("FROM instruments i"), anyRowMapper(),
                eq(1001L), eq("CROSS"), eq(1001L), eq("CROSS"), eq("NET"),
                eq(1001L), eq("CROSS"), eq("NET"),
                eq("BUY"), isNull(),
                eq("BTC-USDT-240927"), eq(1L), eq("LIMIT")))
                .thenReturn(List.of());

        repository.requirement("BTC-USDT-240927", 1L, 1001L, MarginMode.CROSS,
                OrderSide.BUY, OrderType.LIMIT, 100L, 4L, 10_000L, 5_000L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq(1001L), eq("CROSS"), eq(1001L), eq("CROSS"), eq("NET"),
                eq(1001L), eq("CROSS"), eq("NET"),
                eq("BUY"), isNull(),
                eq("BTC-USDT-240927"), eq(1L), eq("LIMIT"));
        assertThat(sql.getValue())
                .contains("oi.product_line = CASE i.contract_type")
                .contains("LEFT JOIN trading_leverage_settings ls")
                .contains("ls.product_line = CASE i.contract_type")
                .contains("p.product_line = CASE i.contract_type")
                .contains("o.product_line = CASE i.contract_type")
                .contains("WHEN 'LINEAR_DELIVERY' THEN 'LINEAR_DELIVERY'")
                .contains("WHEN 'VANILLA_OPTION' THEN 'OPTION'");
    }

    @Test
    void requirementRejectsProjectedPositionAboveInstrumentLimit() throws Exception {
        OrderMarginRepository repository = new OrderMarginRepository(jdbcTemplate, orderRepository);
        when(jdbcTemplate.query(contains("FROM instruments i"), anyRowMapper(),
                eq(1001L), eq("CROSS"), eq(1001L), eq("CROSS"), eq("NET"),
                eq(1001L), eq("CROSS"), eq("NET"),
                eq("BUY"), isNull(),
                eq("BTC-USDT"), eq(1L), eq("LIMIT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(instrumentRequirementRow(2L, 5_000L), 0));
                });

        var requirement = repository.requirement("BTC-USDT", 1L, 1001L, MarginMode.CROSS,
                OrderSide.BUY, OrderType.LIMIT, 100L, 4L, 10_000L, 5_000L);

        assertThat(requirement).isPresent();
        assertThat(requirement.get().accepted()).isFalse();
        assertThat(requirement.get().rejectReason()).isEqualTo("position notional exceeds instrument limit");
    }

    @Test
    void requirementRejectsProjectedPositionAboveRiskBracketCap() throws Exception {
        OrderMarginRepository repository = new OrderMarginRepository(jdbcTemplate, orderRepository);
        when(jdbcTemplate.query(contains("FROM instruments i"), anyRowMapper(),
                eq(1001L), eq("CROSS"), eq(1001L), eq("CROSS"), eq("NET"),
                eq(1001L), eq("CROSS"), eq("NET"),
                eq("BUY"), isNull(),
                eq("BTC-USDT"), eq(1L), eq("LIMIT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(instrumentRequirementRow(2L, 10_000L), 0));
                });
        when(jdbcTemplate.query(contains("FROM instrument_risk_brackets"), anyRowMapper(),
                eq("BTC-USDT"), eq(1L), eq(6_000L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(riskBracketRow(5_000L), 0));
                });

        var requirement = repository.requirement("BTC-USDT", 1L, 1001L, MarginMode.CROSS,
                OrderSide.BUY, OrderType.LIMIT, 100L, 4L, 10_000L, 5_000L);

        assertThat(requirement).isPresent();
        assertThat(requirement.get().accepted()).isFalse();
        assertThat(requirement.get().rejectReason()).isEqualTo("position notional exceeds risk bracket");
    }

    @Test
    void requirementIncludesPendingSameSideOpenOrdersInProjectedLimit() throws Exception {
        OrderMarginRepository repository = new OrderMarginRepository(jdbcTemplate, orderRepository);
        when(jdbcTemplate.query(contains("FROM instruments i"), anyRowMapper(),
                eq(1001L), eq("CROSS"), eq(1001L), eq("CROSS"), eq("NET"),
                eq(1001L), eq("CROSS"), eq("NET"),
                eq("BUY"), isNull(),
                eq("BTC-USDT"), eq(1L), eq("LIMIT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(instrumentRequirementRow(2L, 3L, 8_000L), 0));
                });

        var requirement = repository.requirement("BTC-USDT", 1L, 1001L, MarginMode.CROSS,
                OrderSide.BUY, OrderType.LIMIT, 100L, 4L, 10_000L, 5_000L);

        assertThat(requirement).isPresent();
        assertThat(requirement.get().accepted()).isFalse();
        assertThat(requirement.get().rejectReason()).isEqualTo("position notional exceeds instrument limit");
    }

    @Test
    void requirementRejectsProjectedPositionAboveOpenInterestLimit() throws Exception {
        OrderMarginRepository repository = new OrderMarginRepository(jdbcTemplate, orderRepository);
        when(jdbcTemplate.query(contains("FROM instruments i"), anyRowMapper(),
                eq(1001L), eq("CROSS"), eq(1001L), eq("CROSS"), eq("NET"),
                eq(1001L), eq("CROSS"), eq("NET"),
                eq("BUY"), isNull(),
                eq("BTC-USDT"), eq(1L), eq("LIMIT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(instrumentRequirementRow(
                            0L, 0L, 100_000L, 300_000L, 5_000L, 100L), 0));
                });

        var requirement = repository.requirement("BTC-USDT", 1L, 1001L, MarginMode.CROSS,
                OrderSide.BUY, OrderType.LIMIT, 100L, 40L, 10_000L, 5_000L);

        assertThat(requirement).isPresent();
        assertThat(requirement.get().accepted()).isFalse();
        assertThat(requirement.get().rejectReason()).isEqualTo("position notional exceeds open interest limit");
    }


    @Test
    void requirementAllowsOrderThatReducesProjectedPositionNotional() throws Exception {
        OrderMarginRepository repository = new OrderMarginRepository(jdbcTemplate, orderRepository);
        when(jdbcTemplate.query(contains("FROM instruments i"), anyRowMapper(),
                eq(1001L), eq("CROSS"), eq(1001L), eq("CROSS"), eq("NET"),
                eq(1001L), eq("CROSS"), eq("NET"),
                eq("SELL"), isNull(),
                eq("BTC-USDT"), eq(1L), eq("LIMIT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(instrumentRequirementRow(10L, 5_000L), 0));
                });
        when(jdbcTemplate.query(contains("FROM instrument_risk_brackets"), anyRowMapper(),
                eq("BTC-USDT"), eq(1L), eq(2_000L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(riskBracketRow(5_000L), 0));
                });

        var requirement = repository.requirement("BTC-USDT", 1L, 1001L, MarginMode.CROSS,
                OrderSide.SELL, OrderType.LIMIT, 100L, 8L, 10_000L, 5_000L);

        assertThat(requirement).isPresent();
        assertThat(requirement.get().accepted()).isTrue();
        assertThat(requirement.get().initialMarginUnits()).isEqualTo(80L);
    }

    @Test
    void inversePerpetualRequirementUsesCoinPerpetualAccount() throws Exception {
        OrderMarginRepository repository = new OrderMarginRepository(jdbcTemplate, orderRepository);
        when(jdbcTemplate.query(contains("FROM instruments i"), anyRowMapper(),
                eq(1001L), eq("CROSS"), eq(1001L), eq("CROSS"), eq("NET"),
                eq(1001L), eq("CROSS"), eq("NET"),
                eq("SELL"), isNull(),
                eq("BTC-USD"), eq(1L), eq("LIMIT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(instrumentRequirementRow(
                            0L, 0L, 100_000L, 300_000L, 50_000L, 0L,
                            "INVERSE_PERPETUAL", "BTC"), 0));
                });
        when(jdbcTemplate.query(contains("FROM instrument_risk_brackets"), anyRowMapper(),
                eq("BTC-USD"), eq(1L), eq(10_000L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(riskBracketRow(100_000L), 0));
                });

        var requirement = repository.requirement("BTC-USD", 1L, 1001L, MarginMode.CROSS,
                OrderSide.SELL, OrderType.LIMIT, 100_000L, 1L, 10_000L, 5_000L);

        assertThat(requirement).isPresent();
        assertThat(requirement.get().accepted()).isTrue();
        assertThat(requirement.get().accountType()).isEqualTo("COIN_PERPETUAL");
        assertThat(requirement.get().asset()).isEqualTo("BTC");
    }

    @Test
    void linearDeliveryRequirementUsesUsdtDeliveryAccount() throws Exception {
        OrderMarginRepository repository = new OrderMarginRepository(jdbcTemplate, orderRepository);
        when(jdbcTemplate.query(contains("FROM instruments i"), anyRowMapper(),
                eq(1001L), eq("CROSS"), eq(1001L), eq("CROSS"), eq("NET"),
                eq(1001L), eq("CROSS"), eq("NET"),
                eq("BUY"), isNull(),
                eq("BTC-USDT-240927"), eq(1L), eq("LIMIT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(instrumentRequirementRow(
                            0L, 0L, 100_000L, 300_000L, 50_000L, 0L,
                            "LINEAR_DELIVERY", "USDT"), 0));
                });
        when(jdbcTemplate.query(contains("FROM instrument_risk_brackets"), anyRowMapper(),
                eq("BTC-USDT-240927"), eq(1L), eq(4_000L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(riskBracketRow(100_000L), 0));
                });

        var requirement = repository.requirement("BTC-USDT-240927", 1L, 1001L, MarginMode.CROSS,
                OrderSide.BUY, OrderType.LIMIT, 100L, 4L, 10_000L, 5_000L);

        assertThat(requirement).isPresent();
        assertThat(requirement.get().accepted()).isTrue();
        assertThat(requirement.get().accountType()).isEqualTo("USDT_DELIVERY");
        assertThat(requirement.get().asset()).isEqualTo("USDT");
    }

    @Test
    void inverseDeliveryRequirementUsesCoinDeliveryAccount() throws Exception {
        OrderMarginRepository repository = new OrderMarginRepository(jdbcTemplate, orderRepository);
        when(jdbcTemplate.query(contains("FROM instruments i"), anyRowMapper(),
                eq(1001L), eq("CROSS"), eq(1001L), eq("CROSS"), eq("NET"),
                eq(1001L), eq("CROSS"), eq("NET"),
                eq("SELL"), isNull(),
                eq("BTC-USD-240927"), eq(1L), eq("LIMIT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(instrumentRequirementRow(
                            0L, 0L, 100_000L, 300_000L, 50_000L, 0L,
                            "INVERSE_DELIVERY", "BTC"), 0));
                });
        when(jdbcTemplate.query(contains("FROM instrument_risk_brackets"), anyRowMapper(),
                eq("BTC-USD-240927"), eq(1L), eq(10_000L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(riskBracketRow(100_000L), 0));
                });

        var requirement = repository.requirement("BTC-USD-240927", 1L, 1001L, MarginMode.CROSS,
                OrderSide.SELL, OrderType.LIMIT, 100_000L, 1L, 10_000L, 5_000L);

        assertThat(requirement).isPresent();
        assertThat(requirement.get().accepted()).isTrue();
        assertThat(requirement.get().accountType()).isEqualTo("COIN_DELIVERY");
        assertThat(requirement.get().asset()).isEqualTo("BTC");
    }

    @Test
    void optionBuyRequirementUsesPremiumFromOptionAccount() throws Exception {
        OrderMarginRepository repository = new OrderMarginRepository(jdbcTemplate, orderRepository);
        when(jdbcTemplate.query(contains("FROM instruments i"), anyRowMapper(),
                eq(1001L), eq("CROSS"), eq(1001L), eq("CROSS"), eq("NET"),
                eq(1001L), eq("CROSS"), eq("NET"),
                eq("BUY"), isNull(),
                eq("BTC-USDT-260925-70000-C"), eq(1L), eq("LIMIT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(instrumentRequirementRow(
                            0L, 0L, 100_000L, 300_000L, 50_000L, 0L,
                            "VANILLA_OPTION", "USDT"), 0));
                });
        when(jdbcTemplate.query(contains("FROM instrument_risk_brackets"), anyRowMapper(),
                eq("BTC-USDT-260925-70000-C"), eq(1L), eq(4_000L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(riskBracketRow(100_000L), 0));
                });

        var requirement = repository.requirement("BTC-USDT-260925-70000-C", 1L, 1001L,
                MarginMode.CROSS, OrderSide.BUY, OrderType.LIMIT, 100L, 4L, 10_000L, 5_000L);

        assertThat(requirement).isPresent();
        assertThat(requirement.get().accepted()).isTrue();
        assertThat(requirement.get().accountType()).isEqualTo("OPTION");
        assertThat(requirement.get().initialMarginUnits()).isEqualTo(4_000L);
    }

    @Test
    void optionSellRequirementAddsSingleLegSellerRisk() throws Exception {
        OrderMarginRepository repository = new OrderMarginRepository(jdbcTemplate, orderRepository);
        when(jdbcTemplate.query(contains("FROM instruments i"), anyRowMapper(),
                eq(1001L), eq("CROSS"), eq(1001L), eq("CROSS"), eq("NET"),
                eq(1001L), eq("CROSS"), eq("NET"),
                eq("SELL"), isNull(),
                eq("BTC-USDT-260925-70000-C"), eq(1L), eq("LIMIT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = instrumentRequirementRow(
                            0L, 0L, 100_000L, 300_000L, 50_000L, 0L,
                            "VANILLA_OPTION", "USDT");
                    when(rs.getLong("mark_ticks")).thenReturn(99L);
                    when(rs.wasNull()).thenReturn(false, true);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.query(contains("FROM instrument_risk_brackets"), anyRowMapper(),
                eq("BTC-USDT-260925-70000-C"), eq(1L), eq(4_000L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(riskBracketRow(100_000L), 0));
                });

        var requirement = repository.requirement("BTC-USDT-260925-70000-C", 1L, 1001L,
                MarginMode.CROSS, OrderSide.SELL, OrderType.LIMIT, 100L, 4L, 10_000L, 5_000L);

        assertThat(requirement).isPresent();
        assertThat(requirement.get().accepted()).isTrue();
        assertThat(requirement.get().accountType()).isEqualTo("OPTION");
        assertThat(requirement.get().initialMarginUnits()).isEqualTo(4_040L);
    }

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

    @Test
    void reserveUsesProductBalanceForDeliveryMarginAccount() throws Exception {
        OrderMarginRepository repository = new OrderMarginRepository(jdbcTemplate, orderRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(contains("FROM account_product_balances"), anyRowMapper(),
                eq("USDT_DELIVERY"), eq(1001L), eq("USDT")))
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
        when(jdbcTemplate.update(contains("UPDATE account_product_balances"), any(Object[].class)))
                .thenReturn(1);

        boolean reserved = repository.reserve(1001L, "USDT_DELIVERY", "USDT", 9002L, "BTC-USDT-240927",
                MarginMode.CROSS, 500L, now);

        assertThat(reserved).isTrue();
        verify(jdbcTemplate).update(contains("UPDATE account_product_balances"),
                eq(500L), eq(500L), any(Timestamp.class), eq("USDT_DELIVERY"), eq(1001L), eq("USDT"), eq(500L));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
    }

    private ResultSet instrumentRequirementRow(long currentSignedQuantitySteps,
                                               long maxPositionNotionalUnits) throws Exception {
        return instrumentRequirementRow(currentSignedQuantitySteps, 0L, maxPositionNotionalUnits);
    }

    private ResultSet instrumentRequirementRow(long currentSignedQuantitySteps,
                                               long pendingSameSideSteps,
                                               long maxPositionNotionalUnits) throws Exception {
        return instrumentRequirementRow(currentSignedQuantitySteps, pendingSameSideSteps,
                maxPositionNotionalUnits, 300_000L, 50_000L, 0L);
    }

    private ResultSet instrumentRequirementRow(long currentSignedQuantitySteps,
                                               long pendingSameSideSteps,
                                               long maxPositionNotionalUnits,
                                               long openInterestLimitRatePpm,
                                               long openInterestLimitFloorUnits,
                                               long symbolOpenQuantitySteps) throws Exception {
        return instrumentRequirementRow(currentSignedQuantitySteps, pendingSameSideSteps, maxPositionNotionalUnits,
                openInterestLimitRatePpm, openInterestLimitFloorUnits, symbolOpenQuantitySteps,
                "LINEAR_PERPETUAL", "USDT");
    }

    private ResultSet instrumentRequirementRow(long currentSignedQuantitySteps,
                                               long pendingSameSideSteps,
                                               long maxPositionNotionalUnits,
                                               long openInterestLimitRatePpm,
                                               long openInterestLimitFloorUnits,
                                               long symbolOpenQuantitySteps,
                                               String contractType,
                                               String asset) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("contract_type")).thenReturn(contractType);
        when(rs.getString("asset")).thenReturn(asset);
        when(rs.getLong("notional_multiplier_units")).thenReturn(10L);
        when(rs.getLong("price_tick_units")).thenReturn(1L);
        when(rs.getLong("settle_scale_units")).thenReturn(100_000_000L);
        when(rs.getLong("initial_margin_rate_ppm")).thenReturn(10_000L);
        when(rs.getLong("max_leverage_ppm")).thenReturn(100_000_000L);
        when(rs.getLong("max_position_notional_units")).thenReturn(maxPositionNotionalUnits);
        when(rs.getLong("user_open_interest_limit_rate_ppm")).thenReturn(openInterestLimitRatePpm);
        when(rs.getLong("user_open_interest_limit_floor_units")).thenReturn(openInterestLimitFloorUnits);
        when(rs.getLong("current_signed_quantity_steps")).thenReturn(currentSignedQuantitySteps);
        when(rs.getLong("pending_same_side_steps")).thenReturn(pendingSameSideSteps);
        when(rs.getLong("symbol_open_quantity_steps")).thenReturn(symbolOpenQuantitySteps);
        when(rs.wasNull()).thenReturn(true);
        return rs;
    }

    private ResultSet riskBracketRow(long notionalCapUnits) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("max_leverage_ppm")).thenReturn(100_000_000L);
        when(rs.getLong("initial_margin_rate_ppm")).thenReturn(10_000L);
        when(rs.getLong("notional_cap_units")).thenReturn(notionalCapUnits);
        return rs;
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
