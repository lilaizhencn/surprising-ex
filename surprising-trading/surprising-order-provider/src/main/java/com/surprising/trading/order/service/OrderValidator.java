package com.surprising.trading.order.service;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.trading.api.model.MarketPriceProtection;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import com.surprising.trading.order.model.MarkPriceLookup;
import com.surprising.trading.order.model.ValidationResult;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OrderValidator {

    private final InstrumentRuleLookup instrumentRuleLookup;
    private final TradingOrderProperties properties;
    private final MarkPriceLookup markPriceLookup;

    public OrderValidator(InstrumentRuleLookup instrumentRuleLookup) {
        this(instrumentRuleLookup, new TradingOrderProperties(), (symbol, instrumentVersion, maxAgeMs) -> OptionalLong.empty());
    }

    @Autowired
    public OrderValidator(InstrumentRuleLookup instrumentRuleLookup,
                          TradingOrderProperties properties,
                          MarkPriceLookup markPriceLookup) {
        this.instrumentRuleLookup = instrumentRuleLookup;
        this.properties = properties;
        this.markPriceLookup = markPriceLookup;
    }

    public ValidationResult validate(PlaceOrderRequest request) {
        if (request.userId() <= 0) {
            return ValidationResult.reject("userId must be positive");
        }
        if (request.side() == null || request.orderType() == null || request.timeInForce() == null) {
            return ValidationResult.reject("side, orderType and timeInForce are required");
        }
        if (request.quantitySteps() <= 0) {
            return ValidationResult.reject("quantitySteps must be positive");
        }

        InstrumentRule rule = instrumentRuleLookup.currentRule(request.symbol())
                .orElse(null);
        if (rule == null) {
            return ValidationResult.reject("unknown symbol");
        }
        ValidationResult tradingMode = validateInstrumentTradingMode(request, rule);
        if (!tradingMode.accepted()) {
            return tradingMode;
        }
        if (!rule.supportedOrderTypes().contains(request.orderType().name())) {
            return ValidationResult.reject("order type is not supported", rule.version(), rule.instrumentType());
        }
        if (!rule.supportedTimeInForce().contains(request.timeInForce().name())) {
            return ValidationResult.reject("time in force is not supported", rule.version(), rule.instrumentType());
        }
        if (request.quantitySteps() < rule.minQuantitySteps()) {
            return ValidationResult.reject("quantity is below minimum step limit", rule.version(), rule.instrumentType());
        }
        if (request.quantitySteps() > rule.maxQuantitySteps()) {
            return ValidationResult.reject("quantity is above maximum step limit", rule.version(), rule.instrumentType());
        }
        if (request.reduceOnly() && rule.spot()) {
            return ValidationResult.reject("reduce-only is only supported for perpetual instruments",
                    rule.version(), rule.instrumentType());
        }
        if (request.reduceOnly() && !rule.reduceOnlyEnabled()) {
            return ValidationResult.reject("reduce-only is disabled", rule.version(), rule.instrumentType());
        }
        if (request.postOnly() && !rule.postOnlyEnabled()) {
            return ValidationResult.reject("post-only is disabled", rule.version(), rule.instrumentType());
        }
        if (request.timeInForce() == TimeInForce.GTX && (!request.postOnly() || request.orderType() != OrderType.LIMIT)) {
            return ValidationResult.reject("GTX requires a post-only limit order", rule.version(), rule.instrumentType());
        }
        if (request.postOnly() && request.orderType() != OrderType.LIMIT) {
            return ValidationResult.reject("post-only requires a limit order", rule.version(), rule.instrumentType());
        }
        if (request.orderType() == OrderType.MARKET) {
            return validateMarket(request, rule);
        }
        return validateLimit(request, rule);
    }

    private ValidationResult validateInstrumentTradingMode(PlaceOrderRequest request, InstrumentRule rule) {
        String status = rule.status();
        if ("TRADING".equals(status)) {
            return ValidationResult.ok(rule.version(), rule.instrumentType());
        }
        if ("SETTLING".equals(status)) {
            if (request.reduceOnly()) {
                return ValidationResult.ok(rule.version(), rule.instrumentType());
            }
            return ValidationResult.reject("instrument is reduce-only", rule.version(), rule.instrumentType());
        }
        if ("HALT".equals(status)) {
            return ValidationResult.reject("instrument is cancel-only", rule.version(), rule.instrumentType());
        }
        return ValidationResult.reject("instrument is not trading", rule.version(), rule.instrumentType());
    }

    private ValidationResult validateMarket(PlaceOrderRequest request, InstrumentRule rule) {
        if (!rule.marketOrderEnabled()) {
            return ValidationResult.reject("market order is disabled", rule.version(), rule.instrumentType());
        }
        if (request.priceTicks() != 0) {
            return ValidationResult.reject("market order priceTicks must be zero", rule.version(), rule.instrumentType());
        }
        if (request.timeInForce() != TimeInForce.IOC && request.timeInForce() != TimeInForce.FOK) {
            return ValidationResult.reject("market order requires IOC or FOK", rule.version(), rule.instrumentType());
        }
        OptionalLong markPriceTicks = markPriceLookup.latestMarkPriceTicks(request.symbol(), rule.version(),
                properties.getRisk().getMarketMaxMarkAgeMs());
        if (markPriceTicks.isEmpty()) {
            return ValidationResult.reject("mark price unavailable", rule.version(), rule.instrumentType());
        }
        long lowerPriceTicks;
        long upperPriceTicks;
        try {
            lowerPriceTicks = OrderMarginMath.lowerBoundPriceTicks(request.orderType(), request.priceTicks(),
                    markPriceTicks.getAsLong(), properties.getRisk().getMarketMaxSlippagePpm());
            upperPriceTicks = OrderMarginMath.upperBoundPriceTicks(request.orderType(), request.priceTicks(),
                    markPriceTicks.getAsLong(), properties.getRisk().getMarketMaxSlippagePpm());
        } catch (ArithmeticException ex) {
            return ValidationResult.reject("notional overflow", rule.version(), rule.instrumentType());
        }
        return validateNotionalRange(request, rule, lowerPriceTicks, upperPriceTicks);
    }

    private ValidationResult validateLimit(PlaceOrderRequest request, InstrumentRule rule) {
        if (request.priceTicks() <= 0) {
            return ValidationResult.reject("limit order priceTicks must be positive", rule.version(), rule.instrumentType());
        }
        ValidationResult priceBand = validateLimitPriceBand(request, rule);
        if (!priceBand.accepted()) {
            return priceBand;
        }
        return validateNotionalRange(request, rule, request.priceTicks(), request.priceTicks());
    }

    private ValidationResult validateLimitPriceBand(PlaceOrderRequest request, InstrumentRule rule) {
        if (!properties.getRisk().isLimitPriceProtectionEnabled()) {
            return ValidationResult.ok(rule.version(), rule.instrumentType());
        }
        OptionalLong markPriceTicks = markPriceLookup.latestMarkPriceTicks(request.symbol(), rule.version(),
                properties.getRisk().getLimitPriceMaxMarkAgeMs());
        if (markPriceTicks.isEmpty()) {
            return ValidationResult.reject("mark price unavailable", rule.version(), rule.instrumentType());
        }
        long boundaryTicks;
        try {
            boundaryTicks = MarketPriceProtection.protectedPriceTicks(request.side(), markPriceTicks.getAsLong(),
                    properties.getRisk().getLimitPriceBandPpm());
        } catch (ArithmeticException ex) {
            return ValidationResult.reject("price protection overflow", rule.version(), rule.instrumentType());
        }
        if (request.side() == OrderSide.BUY && request.priceTicks() > boundaryTicks) {
            return ValidationResult.reject("limit buy price exceeds mark price band", rule.version(), rule.instrumentType());
        }
        if (request.side() == OrderSide.SELL && request.priceTicks() < boundaryTicks) {
            return ValidationResult.reject("limit sell price exceeds mark price band", rule.version(), rule.instrumentType());
        }
        return ValidationResult.ok(rule.version(), rule.instrumentType());
    }

    private ValidationResult validateNotionalRange(PlaceOrderRequest request,
                                                   InstrumentRule rule,
                                                   long lowerPriceTicks,
                                                   long upperPriceTicks) {
        long minExecutionNotionalUnits;
        long maxExecutionNotionalUnits;
        try {
            // Reject overflow instead of wrapping notional and accepting an unsafe order.
            minExecutionNotionalUnits = notionalUnits(request, rule, lowerPriceTicks);
            maxExecutionNotionalUnits = lowerPriceTicks == upperPriceTicks
                    ? minExecutionNotionalUnits
                    : notionalUnits(request, rule, upperPriceTicks);
        } catch (ArithmeticException ex) {
            return ValidationResult.reject("notional overflow", rule.version(), rule.instrumentType());
        }
        if (minExecutionNotionalUnits < rule.minNotionalUnits()) {
            return ValidationResult.reject("notional is below minimum limit", rule.version(), rule.instrumentType());
        }
        if (maxExecutionNotionalUnits > rule.maxNotionalUnits()) {
            return ValidationResult.reject("notional is above maximum limit", rule.version(), rule.instrumentType());
        }
        return ValidationResult.ok(rule.version(), rule.instrumentType());
    }

    private long notionalUnits(PlaceOrderRequest request, InstrumentRule rule, long effectivePriceTicks) {
        if (rule.contractType() == ContractType.INVERSE_PERPETUAL) {
            return Math.multiplyExact(request.quantitySteps(), rule.notionalMultiplierUnits());
        }
        return Math.multiplyExact(Math.multiplyExact(effectivePriceTicks, request.quantitySteps()),
                rule.notionalMultiplierUnits());
    }
}
