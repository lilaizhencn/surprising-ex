package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderValidatorTest {

    @Test
    void acceptsLimitOrderWithinStepAndNotionalRules() {
        OrderValidator validator = new OrderValidator(lookup(tradingRule()));

        var result = validator.validate(limit(300_000L, 2L));

        assertThat(result.accepted()).isTrue();
        assertThat(result.rejectReason()).isNull();
    }

    @Test
    void rejectsLimitOrderWhenNotionalOverflowsLong() {
        OrderValidator validator = new OrderValidator(lookup(tradingRule()));

        var result = validator.validate(limit(Long.MAX_VALUE, 2L));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("notional overflow");
    }

    @Test
    void rejectsMarketOrderWithPriceTicks() {
        OrderValidator validator = new OrderValidator(lookup(tradingRule()));
        var request = new PlaceOrderRequest(1001L, "c1", "BTC-USDT", OrderSide.BUY,
                OrderType.MARKET, TimeInForce.IOC, 1L, 10L, false, false);

        var result = validator.validate(request);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("market order priceTicks must be zero");
    }

    @Test
    void rejectsGtcMarketOrder() {
        OrderValidator validator = new OrderValidator(lookup(tradingRule()));
        var request = new PlaceOrderRequest(1001L, "c1", "BTC-USDT", OrderSide.BUY,
                OrderType.MARKET, TimeInForce.GTC, 0L, 10L, false, false);

        var result = validator.validate(request);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("market order requires IOC or FOK");
    }

    @Test
    void acceptsInverseLimitOrderByContractFaceValue() {
        OrderValidator validator = new OrderValidator(lookup(inverseRule()));

        var result = validator.validate(limit(1_000_000L, 10L));

        assertThat(result.accepted()).isTrue();
        assertThat(result.rejectReason()).isNull();
    }

    @Test
    void rejectsMarketOrderWithoutFreshMarkPrice() {
        OrderValidator validator = validator(tradingRule(), OptionalLong.empty(), 10_000L);
        var request = market(10L);

        var result = validator.validate(request);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("mark price unavailable");
    }

    @Test
    void rejectsMarketOrderWhenProtectedNotionalExceedsMaxLimit() {
        InstrumentRule rule = new InstrumentRule(
                "BTC-USDT",
                1L,
                "TRADING",
                ContractType.LINEAR_PERPETUAL,
                Set.of("LIMIT", "MARKET"),
                Set.of("GTC", "IOC", "FOK", "GTX"),
                true,
                true,
                true,
                1L,
                100_000L,
                1L,
                1_000L,
                1L,
                100_000_000L,
                10_000L);
        OrderValidator validator = validator(rule, OptionalLong.of(100L), 10_000L);

        var result = validator.validate(market(10L));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("notional is above maximum limit");
    }

    @Test
    void rejectsLinearSellMarketOrderWhenUpperBoundNotionalExceedsMaxLimit() {
        InstrumentRule rule = new InstrumentRule(
                "BTC-USDT",
                1L,
                "TRADING",
                ContractType.LINEAR_PERPETUAL,
                Set.of("LIMIT", "MARKET"),
                Set.of("GTC", "IOC", "FOK", "GTX"),
                true,
                true,
                true,
                1L,
                100_000L,
                1L,
                1_000L,
                1L,
                100_000_000L,
                10_000L);
        OrderValidator validator = validator(rule, OptionalLong.of(100L), 10_000L);

        var result = validator.validate(market(OrderSide.SELL, 10L));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("notional is above maximum limit");
    }

    @Test
    void rejectsMarketOrderWhenLowerBoundNotionalIsBelowMinLimit() {
        InstrumentRule rule = new InstrumentRule(
                "BTC-USDT",
                1L,
                "TRADING",
                ContractType.LINEAR_PERPETUAL,
                Set.of("LIMIT", "MARKET"),
                Set.of("GTC", "IOC", "FOK", "GTX"),
                true,
                true,
                true,
                1L,
                100_000L,
                100L,
                1_000_000L,
                1L,
                100_000_000L,
                10_000L);
        OrderValidator validator = validator(rule, OptionalLong.of(100L), 10_000L);

        var result = validator.validate(market(OrderSide.BUY, 1L));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("notional is below minimum limit");
    }

    @Test
    void acceptsMarketOrderWithinProtectedNotionalLimits() {
        OrderValidator validator = validator(tradingRule(), OptionalLong.of(100_000L), 10_000L);

        var result = validator.validate(market(1L));

        assertThat(result.accepted()).isTrue();
        assertThat(result.rejectReason()).isNull();
    }

    @Test
    void rejectsHaltedInstrument() {
        OrderValidator validator = new OrderValidator(lookup(new InstrumentRule(
                "BTC-USDT", 1L, "HALT", ContractType.LINEAR_PERPETUAL, Set.of("LIMIT", "MARKET"),
                Set.of("GTC", "IOC", "FOK", "GTX"), true, true, true,
                1L, 100_000L, 50_000L, 100_000_000_000L, 1L, 100_000_000L, 10_000L)));

        var result = validator.validate(limit(300_000L, 2L));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("instrument is not trading");
    }

    private PlaceOrderRequest limit(long priceTicks, long quantitySteps) {
        return new PlaceOrderRequest(1001L, "c1", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, priceTicks, quantitySteps, false, false);
    }

    private PlaceOrderRequest market(long quantitySteps) {
        return market(OrderSide.BUY, quantitySteps);
    }

    private PlaceOrderRequest market(OrderSide side, long quantitySteps) {
        return new PlaceOrderRequest(1001L, "c1", "BTC-USDT", side,
                OrderType.MARKET, TimeInForce.IOC, 0L, quantitySteps, false, false);
    }

    private InstrumentRule tradingRule() {
        return new InstrumentRule(
                "BTC-USDT",
                1L,
                "TRADING",
                ContractType.LINEAR_PERPETUAL,
                Set.of("LIMIT", "MARKET"),
                Set.of("GTC", "IOC", "FOK", "GTX"),
                true,
                true,
                true,
                1L,
                100_000L,
                50_000L,
                100_000_000_000L,
                1L,
                100_000_000L,
                10_000L);
    }

    private InstrumentRule inverseRule() {
        return new InstrumentRule(
                "BTC-USD",
                2L,
                "TRADING",
                ContractType.INVERSE_PERPETUAL,
                Set.of("LIMIT", "MARKET"),
                Set.of("GTC", "IOC", "FOK", "GTX"),
                true,
                true,
                true,
                1L,
                100_000L,
                1_000L,
                10_000L,
                100L,
                100_000_000L,
                10_000L);
    }

    private InstrumentRuleLookup lookup(InstrumentRule rule) {
        return symbol -> Optional.of(rule);
    }

    private OrderValidator validator(InstrumentRule rule, OptionalLong markPriceTicks, long maxSlippagePpm) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getRisk().setMarketMaxSlippagePpm(maxSlippagePpm);
        return new OrderValidator(lookup(rule), properties,
                (symbol, instrumentVersion, maxAgeMs) -> markPriceTicks);
    }
}
