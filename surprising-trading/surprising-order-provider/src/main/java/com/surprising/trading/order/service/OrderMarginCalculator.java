package com.surprising.trading.order.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.MarginRequirement;
import com.surprising.trading.order.model.MarkPriceLookup;
import com.surprising.trading.order.repository.OrderLeverageMath;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** 订单保证金计算入口，只读取合约、未平仓量和用户状态的 JVM 快照。 */
@Service
public class OrderMarginCalculator {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    private final MarkPriceLookup markPriceLookup;
    private final TradingOrderProperties properties;
    private final InstrumentSnapshotCache snapshotCache;
    private final OrderMarginSnapshotCache marginSnapshotCache;
    private final OpenInterestSnapshotCache openInterestSnapshotCache;

    @Autowired
    public OrderMarginCalculator(MarkPriceLookup markPriceLookup,
                                  TradingOrderProperties properties,
                                  @Qualifier("orderInstrumentSnapshotCache") InstrumentSnapshotCache snapshotCache,
                                  OrderMarginSnapshotCache marginSnapshotCache,
                                  OpenInterestSnapshotCache openInterestSnapshotCache) {
        this.markPriceLookup = markPriceLookup;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
        this.marginSnapshotCache = marginSnapshotCache;
        this.openInterestSnapshotCache = openInterestSnapshotCache;
    }

    public Optional<MarginRequirement> requirement(String symbol,
                                                   long instrumentVersion,
                                                   long userId,
                                                   MarginMode marginMode,
                                                   OrderSide side,
                                                   OrderType orderType,
                                                   long priceTicks,
                                                   long quantitySteps,
                                                   long marketMaxSlippagePpm,
                                                   long marketMaxMarkAgeMs) {
        return requirement(symbol, instrumentVersion, userId, marginMode, PositionSide.NET, side, orderType,
                priceTicks, quantitySteps, marketMaxSlippagePpm, marketMaxMarkAgeMs);
    }

    /**
     * 计算下单保证金。任何一个必需快照缺失都会返回空值，调用方应拒绝下单；这里禁止实时查库。
     */
    public Optional<MarginRequirement> requirement(String symbol,
                                                   long instrumentVersion,
                                                   long userId,
                                                   MarginMode marginMode,
                                                   PositionSide positionSide,
                                                   OrderSide side,
                                                   OrderType orderType,
                                                   long priceTicks,
                                                   long quantitySteps,
                                                   long marketMaxSlippagePpm,
                                                   long marketMaxMarkAgeMs) {
        if (snapshotCache == null || properties == null || marginSnapshotCache == null
                || openInterestSnapshotCache == null || markPriceLookup == null) {
            return Optional.empty();
        }
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!openInterestSnapshotCache.ready(productLine)) {
            return Optional.empty();
        }
        long openInterest = openInterestSnapshotCache.lookup(productLine, symbol)
                .map(OpenInterestSnapshotCache.OpenInterestValue::openQuantitySteps)
                .orElse(0L);
        InstrumentResponse instrument = snapshotCache.version(productLine, symbol, instrumentVersion)
                .orElseThrow(() -> new IllegalArgumentException("下单合约快照不存在: " + symbol + "@" + instrumentVersion));
        long settleScaleUnits = snapshotCache.scale(productLine, instrument.settleAsset())
                .orElseThrow(() -> new IllegalArgumentException("结算资产精度快照不存在: " + instrument.settleAsset()));
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        // 没有持仓和未成交订单的新用户不会在启动恢复结果中出现；零仓位和默认杠杆是
        // 合约快照已经确认后的确定状态，直接补入 JVM，避免首单误判为快照缺失。
        marginSnapshotCache.putPositionIfAbsent(productLine, userId, symbol, normalizedMarginMode,
                normalizedPositionSide, instrumentVersion);
        marginSnapshotCache.putDefaultLeverageIfAbsent(productLine, userId, symbol, normalizedMarginMode);
        Optional<OrderMarginSnapshotCache.MarginSnapshot> cached = marginSnapshotCache.lookup(
                        productLine, userId, symbol, normalizedMarginMode, normalizedPositionSide, side)
                .filter(value -> value.instrumentVersion() == instrumentVersion);
        if (cached.isEmpty()) {
            // 快照未命中时失败关闭，避免下单线程执行多表 SQL。
            return Optional.empty();
        }
        Long markPriceTicks = markPriceLookup.latestMarkPriceTicks(symbol, instrumentVersion, marketMaxMarkAgeMs)
                .stream().boxed().findFirst().orElse(null);
        MarginInputs input = new MarginInputs(instrument.contractType(), instrument.settleAsset(),
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), settleScaleUnits,
                instrument.initialMarginRatePpm(), instrument.maxLeveragePpm(), instrument.maxPositionNotionalUnits(),
                instrument.userOpenInterestLimitRatePpm(), instrument.userOpenInterestLimitFloorUnits(),
                cached.get().configuredLeveragePpm(), cached.get().currentSignedQuantitySteps(),
                cached.get().pendingSameSideSteps(), openInterest, markPriceTicks);
        return Optional.of(calculate(input, instrument, side, orderType, priceTicks, quantitySteps,
                marketMaxSlippagePpm));
    }

    public Optional<MarginRequirement> requirement(String symbol,
                                                   long instrumentVersion,
                                                   OrderSide side,
                                                   OrderType orderType,
                                                   long priceTicks,
                                                   long quantitySteps,
                                                   long marketMaxSlippagePpm,
                                                   long marketMaxMarkAgeMs) {
        return requirement(symbol, instrumentVersion, 0L, MarginMode.CROSS, PositionSide.NET, side, orderType,
                priceTicks, quantitySteps, marketMaxSlippagePpm, marketMaxMarkAgeMs);
    }

    private MarginRequirement calculate(MarginInputs input,
                                        InstrumentResponse instrument,
                                        OrderSide side,
                                        OrderType orderType,
                                        long priceTicks,
                                        long quantitySteps,
                                        long marketMaxSlippagePpm) {
        String accountType = accountType(input.contractType());
        boolean protectAdverseFillPrice = mayOpenExposure(input.currentSignedQuantitySteps(), side,
                input.pendingSameSideSteps(), quantitySteps);
        long effectivePriceTicks;
        try {
            effectivePriceTicks = OrderMarginMath.collateralPriceTicks(side, orderType, priceTicks,
                    input.markTicks(), marketMaxSlippagePpm, input.contractType(), protectAdverseFillPrice);
        } catch (IllegalArgumentException ex) {
            return rejected(accountType, input, ex.getMessage(), leverage(input),
                    input.instrumentMaxLeveragePpm(), input.instrumentInitialMarginRatePpm());
        }
        long projected = projectedPositionNotionalUnits(input.contractType(), input.currentSignedQuantitySteps(), side,
                Math.addExact(input.pendingSameSideSteps(), quantitySteps), effectivePriceTicks,
                input.notionalMultiplierUnits(), input.priceTickUnits(), input.settleScaleUnits());
        long dynamicLimit = dynamicPositionLimitUnits(input.contractType(), input.symbolOpenQuantitySteps(),
                effectivePriceTicks, input.notionalMultiplierUnits(), input.priceTickUnits(), input.settleScaleUnits(),
                input.openInterestLimitRatePpm(), input.openInterestLimitFloorUnits(), input.maxPositionNotionalUnits());
        if (projected > input.maxPositionNotionalUnits()) {
            return rejected(accountType, input, "position notional exceeds instrument limit", leverage(input),
                    input.instrumentMaxLeveragePpm(), input.instrumentInitialMarginRatePpm());
        }
        if (projected > dynamicLimit) {
            return rejected(accountType, input, "position notional exceeds open interest limit", leverage(input),
                    input.instrumentMaxLeveragePpm(), input.instrumentInitialMarginRatePpm());
        }
        RiskBracket bracket = riskBracket(instrument, projected)
                .orElse(new RiskBracket(input.instrumentMaxLeveragePpm(), input.instrumentInitialMarginRatePpm(),
                        input.maxPositionNotionalUnits()));
        if (projected > bracket.notionalCapUnits()) {
            return rejected(accountType, input, "position notional exceeds risk bracket", leverage(input),
                    bracket.maxLeveragePpm(), bracket.initialMarginRatePpm());
        }
        if (input.configuredLeveragePpm() != null && input.configuredLeveragePpm() > bracket.maxLeveragePpm()) {
            return rejected(accountType, input, "leverage exceeds risk limit", input.configuredLeveragePpm(),
                    bracket.maxLeveragePpm(), bracket.initialMarginRatePpm());
        }
        long selectedLeverage = input.configuredLeveragePpm() == null
                ? bracket.maxLeveragePpm() : input.configuredLeveragePpm();
        long effectiveRate = Math.max(OrderLeverageMath.initialMarginRateFromLeveragePpm(selectedLeverage),
                bracket.initialMarginRatePpm());
        try {
            long initialMargin = OrderMarginMath.initialMarginUnits(input.contractType(), side, orderType,
                    priceTicks, quantitySteps, input.markTicks(), marketMaxSlippagePpm,
                    input.notionalMultiplierUnits(), input.priceTickUnits(), input.settleScaleUnits(), effectiveRate,
                    protectAdverseFillPrice);
            return new MarginRequirement(accountType, input.asset(), initialMargin, null, selectedLeverage,
                    bracket.maxLeveragePpm(), effectiveRate);
        } catch (IllegalArgumentException ex) {
            return rejected(accountType, input, ex.getMessage(), selectedLeverage,
                    bracket.maxLeveragePpm(), effectiveRate);
        }
    }

    private MarginRequirement rejected(String accountType, MarginInputs input, String reason, long leverage,
                                       long maxLeverage, long initialMarginRate) {
        return new MarginRequirement(accountType, input.asset(), 0L, reason, leverage, maxLeverage, initialMarginRate);
    }

    private long leverage(MarginInputs input) {
        return input.configuredLeveragePpm() == null ? 0L : input.configuredLeveragePpm();
    }

    private long projectedPositionNotionalUnits(ContractType contractType, long currentSignedQuantitySteps,
                                                OrderSide side, long orderQuantitySteps, long effectivePriceTicks,
                                                long notionalMultiplierUnits, long priceTickUnits,
                                                long settleScaleUnits) {
        long signedOrderSteps = side == OrderSide.BUY ? orderQuantitySteps : Math.negateExact(orderQuantitySteps);
        long projectedSignedSteps = Math.addExact(currentSignedQuantitySteps, signedOrderSteps);
        long projectedAbsSteps = Math.absExact(projectedSignedSteps);
        return projectedAbsSteps == 0L ? 0L : OrderMarginMath.notionalUnits(contractType, projectedAbsSteps,
                effectivePriceTicks, notionalMultiplierUnits, priceTickUnits, settleScaleUnits);
    }

    private boolean mayOpenExposure(long currentSignedQuantitySteps, OrderSide side, long pendingSameSideSteps,
                                    long orderQuantitySteps) {
        long sameSideSteps = Math.addExact(pendingSameSideSteps, orderQuantitySteps);
        if (side == OrderSide.BUY) {
            return currentSignedQuantitySteps >= 0 || sameSideSteps > Math.absExact(currentSignedQuantitySteps);
        }
        return currentSignedQuantitySteps <= 0 || sameSideSteps > currentSignedQuantitySteps;
    }

    private long dynamicPositionLimitUnits(ContractType contractType, long symbolOpenQuantitySteps,
                                           long effectivePriceTicks, long notionalMultiplierUnits,
                                           long priceTickUnits, long settleScaleUnits, long openInterestLimitRatePpm,
                                           long openInterestLimitFloorUnits, long maxPositionNotionalUnits) {
        if (openInterestLimitRatePpm < 0 || openInterestLimitFloorUnits <= 0) {
            throw new IllegalArgumentException("invalid open interest limit configuration");
        }
        long openInterestNotional = symbolOpenQuantitySteps <= 0 ? 0L : OrderMarginMath.notionalUnits(contractType,
                symbolOpenQuantitySteps, effectivePriceTicks, notionalMultiplierUnits, priceTickUnits, settleScaleUnits);
        long scaled = BigInteger.valueOf(openInterestNotional).multiply(BigInteger.valueOf(openInterestLimitRatePpm))
                .divide(PPM).max(BigInteger.valueOf(openInterestLimitFloorUnits)).longValueExact();
        return Math.min(scaled, maxPositionNotionalUnits);
    }

    private String accountType(ContractType contractType) {
        return contractType.productLine().accountTypeCode();
    }

    private Optional<RiskBracket> riskBracket(InstrumentResponse instrument, long notionalUnits) {
        return instrument.riskLimitBrackets() == null ? Optional.empty() : instrument.riskLimitBrackets().stream()
                .filter(bracket -> bracket.notionalFloorUnits() <= notionalUnits)
                .max(Comparator.comparingLong(bracket -> bracket.notionalFloorUnits()))
                .map(bracket -> new RiskBracket(bracket.maxLeveragePpm(), bracket.initialMarginRatePpm(),
                        bracket.notionalCapUnits()));
    }

    private record RiskBracket(long maxLeveragePpm, long initialMarginRatePpm, long notionalCapUnits) { }

    private record MarginInputs(ContractType contractType, String asset, long notionalMultiplierUnits,
                                long priceTickUnits, long settleScaleUnits, long instrumentInitialMarginRatePpm,
                                long instrumentMaxLeveragePpm, long maxPositionNotionalUnits,
                                long openInterestLimitRatePpm, long openInterestLimitFloorUnits,
                                Long configuredLeveragePpm, long currentSignedQuantitySteps,
                                long pendingSameSideSteps, long symbolOpenQuantitySteps, Long markTicks) { }
}
