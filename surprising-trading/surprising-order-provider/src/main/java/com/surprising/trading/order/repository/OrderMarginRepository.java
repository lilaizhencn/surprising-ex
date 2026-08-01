package com.surprising.trading.order.repository;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.model.MarginRequirement;
import com.surprising.trading.order.model.MarkPriceLookup;
import com.surprising.trading.order.service.OrderMarginMath;
import com.surprising.trading.order.service.OrderMarginSnapshotCache;
import com.surprising.trading.order.service.OpenInterestSnapshotCache;
import java.math.BigInteger;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * 订单保证金与风险限额快照仓储。
 *
 * <p>在线优先使用订单侧 JVM 快照；未平仓量快照尚未就绪时直接失败关闭，不回查数据库或跳过动态持仓
 * 上限校验。其他用户状态快照缺失时仍保留原子 SQL 兜底；不可拆原因是该兜底必须在同一数据库快照中
 * 读取杠杆、持仓和开放订单，避免并发期间形成混合状态。这里属于交易热路径风险校验，不是后台报表查询。</p>
 */
@Repository
public class OrderMarginRepository {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    private final JdbcTemplate jdbcTemplate;
    private final MarkPriceLookup markPriceLookup;
    private final TradingOrderProperties properties;
    private final InstrumentSnapshotCache snapshotCache;
    private final OrderMarginSnapshotCache marginSnapshotCache;
    private final OpenInterestSnapshotCache openInterestSnapshotCache;

    public OrderMarginRepository(JdbcTemplate jdbcTemplate, OrderRepository orderRepository) {
        this(jdbcTemplate, orderRepository, (symbol, version, maxAge) -> java.util.OptionalLong.empty(),
                new TradingOrderProperties(), null, null, null);
    }

    public OrderMarginRepository(JdbcTemplate jdbcTemplate,
                                 OrderRepository orderRepository,
                                 MarkPriceLookup markPriceLookup) {
        this(jdbcTemplate, orderRepository, markPriceLookup, new TradingOrderProperties(), null, null, null);
    }

    @Autowired
    public OrderMarginRepository(JdbcTemplate jdbcTemplate,
                                 OrderRepository orderRepository,
                                 MarkPriceLookup markPriceLookup,
                                 TradingOrderProperties properties,
                                 InstrumentSnapshotCache snapshotCache,
                                 OrderMarginSnapshotCache marginSnapshotCache,
                                 OpenInterestSnapshotCache openInterestSnapshotCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.markPriceLookup = markPriceLookup;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
        this.marginSnapshotCache = marginSnapshotCache;
        this.openInterestSnapshotCache = openInterestSnapshotCache;
    }

    /** 使用已准备好的本地状态计算保证金；未平仓量始终来自本 JVM 快照。 */
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
            return new MarginRequirement(accountType, input.asset(), 0L, ex.getMessage(), leverage(input),
                    input.instrumentMaxLeveragePpm(), input.instrumentInitialMarginRatePpm());
        }
        long projectedPositionNotionalUnits = projectedPositionNotionalUnits(input.contractType(),
                input.currentSignedQuantitySteps(), side,
                Math.addExact(input.pendingSameSideSteps(), quantitySteps), effectivePriceTicks,
                input.notionalMultiplierUnits(), input.priceTickUnits(), input.settleScaleUnits());
        long dynamicPositionLimitUnits = dynamicPositionLimitUnits(input.contractType(),
                input.symbolOpenQuantitySteps(), effectivePriceTicks, input.notionalMultiplierUnits(),
                input.priceTickUnits(), input.settleScaleUnits(), input.openInterestLimitRatePpm(),
                input.openInterestLimitFloorUnits(), input.maxPositionNotionalUnits());
        if (projectedPositionNotionalUnits > input.maxPositionNotionalUnits()) {
            return new MarginRequirement(accountType, input.asset(), 0L,
                    "position notional exceeds instrument limit", leverage(input),
                    input.instrumentMaxLeveragePpm(), input.instrumentInitialMarginRatePpm());
        }
        if (projectedPositionNotionalUnits > dynamicPositionLimitUnits) {
            return new MarginRequirement(accountType, input.asset(), 0L,
                    "position notional exceeds open interest limit", leverage(input),
                    input.instrumentMaxLeveragePpm(), input.instrumentInitialMarginRatePpm());
        }
        RiskBracket bracket = riskBracket(instrument, projectedPositionNotionalUnits)
                .orElse(new RiskBracket(input.instrumentMaxLeveragePpm(), input.instrumentInitialMarginRatePpm(),
                        input.maxPositionNotionalUnits()));
        if (projectedPositionNotionalUnits > bracket.notionalCapUnits()) {
            return new MarginRequirement(accountType, input.asset(), 0L,
                    "position notional exceeds risk bracket", leverage(input), bracket.maxLeveragePpm(),
                    bracket.initialMarginRatePpm());
        }
        if (input.configuredLeveragePpm() != null && input.configuredLeveragePpm() > bracket.maxLeveragePpm()) {
            return new MarginRequirement(accountType, input.asset(), 0L,
                    "leverage exceeds risk limit", input.configuredLeveragePpm(), bracket.maxLeveragePpm(),
                    bracket.initialMarginRatePpm());
        }
        long selectedLeveragePpm = input.configuredLeveragePpm() == null
                ? bracket.maxLeveragePpm() : input.configuredLeveragePpm();
        long leverageInitialMarginRatePpm = OrderLeverageMath.initialMarginRateFromLeveragePpm(selectedLeveragePpm);
        long effectiveInitialMarginRatePpm = Math.max(leverageInitialMarginRatePpm,
                bracket.initialMarginRatePpm());
        try {
            long initialMarginUnits = OrderMarginMath.initialMarginUnits(input.contractType(), side, orderType,
                    priceTicks, quantitySteps, input.markTicks(), marketMaxSlippagePpm,
                    input.notionalMultiplierUnits(), input.priceTickUnits(), input.settleScaleUnits(),
                    effectiveInitialMarginRatePpm, protectAdverseFillPrice);
            return new MarginRequirement(accountType, input.asset(), initialMarginUnits, null,
                    selectedLeveragePpm, bracket.maxLeveragePpm(), effectiveInitialMarginRatePpm);
        } catch (IllegalArgumentException ex) {
            return new MarginRequirement(accountType, input.asset(), 0L, ex.getMessage(),
                    selectedLeveragePpm, bracket.maxLeveragePpm(), effectiveInitialMarginRatePpm);
        }
    }

    private long leverage(MarginInputs input) {
        return input.configuredLeveragePpm() == null ? 0L : input.configuredLeveragePpm();
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
        if (snapshotCache == null || properties == null) {
            return Optional.empty();
        }
        var productLine = properties.getKafka().getProductLine();
        if (openInterestSnapshotCache == null || !openInterestSnapshotCache.ready(productLine)) {
            // 未平仓量快照未恢复时失败关闭，禁止回查数据库或跳过动态持仓上限校验。
            return Optional.empty();
        }
        long openInterest = openInterestSnapshotCache.lookup(productLine, symbol)
                .map(OpenInterestSnapshotCache.OpenInterestValue::openQuantitySteps)
                .orElse(0L);
        InstrumentResponse instrument = snapshotCache.version(productLine, symbol, instrumentVersion)
                .orElseThrow(() -> new IllegalArgumentException("下单合约快照不存在: " + symbol + "@" + instrumentVersion));
        long snapshotSettleScaleUnits = snapshotCache.scale(productLine, instrument.settleAsset())
                .orElseThrow(() -> new IllegalArgumentException("结算资产精度快照不存在: " + instrument.settleAsset()));
        String sql = """
                SELECT i.contract_type,
                       i.settle_asset AS asset,
                       i.notional_multiplier_units,
                       i.price_tick_units,
                       i.initial_margin_rate_ppm,
                       i.max_leverage_ppm,
                       i.max_position_notional_units,
                       i.user_open_interest_limit_rate_ppm,
                       i.user_open_interest_limit_floor_units,
                       i.settle_scale_units,
                       ls.leverage_ppm,
                       COALESCE(p.signed_quantity_steps, 0) AS current_signed_quantity_steps,
                       COALESCE(o.pending_same_side_steps, 0) AS pending_same_side_steps,
                       CAST(? AS bigint) AS symbol_open_quantity_steps,
                       pm.mark_ticks
                  FROM (SELECT CAST(? AS text) AS symbol,
                               CAST(? AS text) AS contract_type,
                               CAST(? AS text) AS settle_asset,
                               CAST(? AS bigint) AS notional_multiplier_units,
                               CAST(? AS bigint) AS price_tick_units,
                               CAST(? AS bigint) AS initial_margin_rate_ppm,
                               CAST(? AS bigint) AS max_leverage_ppm,
                               CAST(? AS bigint) AS max_position_notional_units,
                               CAST(? AS bigint) AS user_open_interest_limit_rate_ppm,
                               CAST(? AS bigint) AS user_open_interest_limit_floor_units,
                               CAST(? AS bigint) AS settle_scale_units) i
             LEFT JOIN trading_leverage_settings ls
                   ON ls.user_id = ?
                   AND ls.symbol = i.symbol
                   AND ls.margin_mode = ?
                   AND ls.product_line = '%s'
             LEFT JOIN account_positions p
                    ON p.user_id = ?
                   AND p.symbol = i.symbol
                   AND p.margin_mode = ?
                   AND p.position_side = ?
                   AND p.product_line = '%s'
                  LEFT JOIN LATERAL (
                      SELECT COALESCE(SUM(o.remaining_quantity_steps), 0) AS pending_same_side_steps
                        FROM trading_orders o
                       WHERE o.user_id = ?
                         AND o.symbol = i.symbol
                         AND o.product_line = '%s'
                         AND o.margin_mode = ?
                         AND o.position_side = ?
                         AND o.side = ?
                         AND o.reduce_only = FALSE
                         AND o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                  ) o ON TRUE
                 CROSS JOIN (SELECT CAST(? AS bigint) AS mark_ticks) pm
                 WHERE (? <> 'MARKET' OR pm.mark_ticks IS NOT NULL)
                """.formatted(productLine.name(), productLine.name(), productLine.name(), productLine.name());
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        Long markPriceTicks = markPriceLookup.latestMarkPriceTicks(symbol, instrumentVersion,
                marketMaxMarkAgeMs).stream().boxed().findFirst().orElse(null);
        if (marginSnapshotCache != null) {
            var cached = marginSnapshotCache.lookup(productLine, userId, symbol, normalizedMarginMode,
                    normalizedPositionSide, side).filter(value -> value.instrumentVersion() == instrumentVersion);
            if (cached.isPresent()) {
                return Optional.of(calculate(new MarginInputs(
                        instrument.contractType(), instrument.settleAsset(), instrument.notionalMultiplierUnits(),
                        instrument.priceTickUnits(), snapshotSettleScaleUnits, instrument.initialMarginRatePpm(),
                        instrument.maxLeveragePpm(), instrument.maxPositionNotionalUnits(),
                        instrument.userOpenInterestLimitRatePpm(), instrument.userOpenInterestLimitFloorUnits(),
                        cached.get().configuredLeveragePpm(), cached.get().currentSignedQuantitySteps(),
                        cached.get().pendingSameSideSteps(), openInterest, markPriceTicks), instrument,
                        side, orderType, priceTicks, quantitySteps, marketMaxSlippagePpm));
            }
        }
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            long markTicks = rs.getLong("mark_ticks");
            Long nullableMarkTicks = rs.wasNull() ? null : markTicks;
            ContractType contractType = ContractType.valueOf(rs.getString("contract_type"));
            String accountType = accountType(contractType);
            long notionalMultiplierUnits = rs.getLong("notional_multiplier_units");
            long priceTickUnits = rs.getLong("price_tick_units");
            long settleScaleUnits = rs.getLong("settle_scale_units");
            long instrumentInitialMarginRatePpm = rs.getLong("initial_margin_rate_ppm");
            long instrumentMaxLeveragePpm = rs.getLong("max_leverage_ppm");
            long maxPositionNotionalUnits = rs.getLong("max_position_notional_units");
            long openInterestLimitRatePpm = rs.getLong("user_open_interest_limit_rate_ppm");
            long openInterestLimitFloorUnits = rs.getLong("user_open_interest_limit_floor_units");
            Long configuredLeveragePpm = nullableLong(rs, "leverage_ppm");
            long currentSignedQuantitySteps = rs.getLong("current_signed_quantity_steps");
            long pendingSameSideSteps = rs.getLong("pending_same_side_steps");
            if (marginSnapshotCache != null) {
                marginSnapshotCache.putPosition(productLine, userId, symbol, normalizedMarginMode,
                        normalizedPositionSide, instrumentVersion, currentSignedQuantitySteps);
                marginSnapshotCache.putLeverage(productLine, userId, symbol, normalizedMarginMode,
                        configuredLeveragePpm);
            }
            boolean protectAdverseFillPrice = mayOpenExposure(currentSignedQuantitySteps, side,
                    pendingSameSideSteps, quantitySteps);
            long effectivePriceTicks;
            try {
                effectivePriceTicks = OrderMarginMath.collateralPriceTicks(side, orderType, priceTicks,
                        nullableMarkTicks, marketMaxSlippagePpm, contractType, protectAdverseFillPrice);
            } catch (IllegalArgumentException ex) {
                return new MarginRequirement(accountType, rs.getString("asset"), 0L, ex.getMessage(),
                        configuredLeveragePpm == null ? 0L : configuredLeveragePpm, instrumentMaxLeveragePpm,
                        instrumentInitialMarginRatePpm);
            }
            long projectedPositionNotionalUnits = projectedPositionNotionalUnits(contractType,
                    currentSignedQuantitySteps, side, Math.addExact(pendingSameSideSteps, quantitySteps),
                    effectivePriceTicks, notionalMultiplierUnits, priceTickUnits, settleScaleUnits);
            long dynamicPositionLimitUnits = dynamicPositionLimitUnits(contractType,
                    rs.getLong("symbol_open_quantity_steps"), effectivePriceTicks, notionalMultiplierUnits,
                    priceTickUnits, settleScaleUnits, openInterestLimitRatePpm, openInterestLimitFloorUnits,
                    maxPositionNotionalUnits);
            if (projectedPositionNotionalUnits > maxPositionNotionalUnits) {
                return new MarginRequirement(accountType, rs.getString("asset"), 0L,
                        "position notional exceeds instrument limit", configuredLeveragePpm == null ? 0L : configuredLeveragePpm,
                        instrumentMaxLeveragePpm, instrumentInitialMarginRatePpm);
            }
            if (projectedPositionNotionalUnits > dynamicPositionLimitUnits) {
                return new MarginRequirement(accountType, rs.getString("asset"), 0L,
                        "position notional exceeds open interest limit", configuredLeveragePpm == null ? 0L : configuredLeveragePpm,
                        instrumentMaxLeveragePpm, instrumentInitialMarginRatePpm);
            }
            // 用户杠杆可按 instrument 保存，但每笔订单仍必须满足当前生效风险档位。
            RiskBracket bracket = riskBracket(instrument, projectedPositionNotionalUnits)
                    .orElse(new RiskBracket(instrumentMaxLeveragePpm, instrumentInitialMarginRatePpm,
                            maxPositionNotionalUnits));
            if (projectedPositionNotionalUnits > bracket.notionalCapUnits()) {
                return new MarginRequirement(accountType, rs.getString("asset"), 0L,
                        "position notional exceeds risk bracket", configuredLeveragePpm == null ? 0L : configuredLeveragePpm,
                        bracket.maxLeveragePpm(), bracket.initialMarginRatePpm());
            }
            if (configuredLeveragePpm != null && configuredLeveragePpm > bracket.maxLeveragePpm()) {
                return new MarginRequirement(accountType, rs.getString("asset"), 0L,
                        "leverage exceeds risk limit", configuredLeveragePpm, bracket.maxLeveragePpm(),
                        bracket.initialMarginRatePpm());
            }
            long selectedLeveragePpm = configuredLeveragePpm == null ? bracket.maxLeveragePpm() : configuredLeveragePpm;
            long leverageInitialMarginRatePpm =
                    OrderLeverageMath.initialMarginRateFromLeveragePpm(selectedLeveragePpm);
            long effectiveInitialMarginRatePpm =
                    Math.max(leverageInitialMarginRatePpm, bracket.initialMarginRatePpm());
            long initialMarginUnits;
            try {
                initialMarginUnits = OrderMarginMath.initialMarginUnits(
                        contractType,
                        side,
                        orderType,
                        priceTicks,
                        quantitySteps,
                        nullableMarkTicks,
                        marketMaxSlippagePpm,
                        notionalMultiplierUnits,
                        priceTickUnits,
                        settleScaleUnits,
                        effectiveInitialMarginRatePpm,
                        protectAdverseFillPrice);
            } catch (IllegalArgumentException ex) {
                return new MarginRequirement(accountType, rs.getString("asset"), 0L, ex.getMessage(),
                        selectedLeveragePpm, bracket.maxLeveragePpm(), effectiveInitialMarginRatePpm);
            }
            return new MarginRequirement(accountType, rs.getString("asset"), initialMarginUnits, null,
                    selectedLeveragePpm, bracket.maxLeveragePpm(), effectiveInitialMarginRatePpm);
        }, openInterest, instrument.symbol(), instrument.contractType().name(), instrument.settleAsset(),
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.initialMarginRatePpm(),
                instrument.maxLeveragePpm(), instrument.maxPositionNotionalUnits(),
                instrument.userOpenInterestLimitRatePpm(), instrument.userOpenInterestLimitFloorUnits(),
                snapshotSettleScaleUnits, userId, normalizedMarginMode.name(), userId, normalizedMarginMode.name(),
                normalizedPositionSide.name(), userId, normalizedMarginMode.name(), normalizedPositionSide.name(),
                side.name(), markPriceTicks, orderType.name()).stream().findFirst();
    }

    public Optional<MarginRequirement> requirement(String symbol,
                                                   long instrumentVersion,
                                                   OrderSide side,
                                                   OrderType orderType,
                                                   long priceTicks,
                                                   long quantitySteps,
                                                   long marketMaxSlippagePpm,
                                                   long marketMaxMarkAgeMs) {
        return requirement(symbol, instrumentVersion, 0L, MarginMode.CROSS, PositionSide.NET, side, orderType, priceTicks,
                quantitySteps, marketMaxSlippagePpm, marketMaxMarkAgeMs);
    }

    private long projectedPositionNotionalUnits(ContractType contractType,
                                                long currentSignedQuantitySteps,
                                                OrderSide side,
                                                long orderQuantitySteps,
                                                long effectivePriceTicks,
                                                long notionalMultiplierUnits,
                                                long priceTickUnits,
                                                long settleScaleUnits) {
        long signedOrderSteps = side == OrderSide.BUY ? orderQuantitySteps : Math.negateExact(orderQuantitySteps);
        long projectedSignedSteps = Math.addExact(currentSignedQuantitySteps, signedOrderSteps);
        long projectedAbsSteps = Math.absExact(projectedSignedSteps);
        if (projectedAbsSteps == 0L) {
            return 0L;
        }
        return OrderMarginMath.notionalUnits(contractType, projectedAbsSteps, effectivePriceTicks,
                notionalMultiplierUnits, priceTickUnits, settleScaleUnits);
    }

    private boolean mayOpenExposure(long currentSignedQuantitySteps,
                                    OrderSide side,
                                    long pendingSameSideSteps,
                                    long orderQuantitySteps) {
        long sameSideSteps = Math.addExact(pendingSameSideSteps, orderQuantitySteps);
        if (side == OrderSide.BUY) {
            if (currentSignedQuantitySteps >= 0) {
                return true;
            }
            return sameSideSteps > Math.absExact(currentSignedQuantitySteps);
        }
        if (currentSignedQuantitySteps <= 0) {
            return true;
        }
        return sameSideSteps > currentSignedQuantitySteps;
    }

    private long dynamicPositionLimitUnits(ContractType contractType,
                                           long symbolOpenQuantitySteps,
                                           long effectivePriceTicks,
                                           long notionalMultiplierUnits,
                                           long priceTickUnits,
                                           long settleScaleUnits,
                                           long openInterestLimitRatePpm,
                                           long openInterestLimitFloorUnits,
                                           long maxPositionNotionalUnits) {
        if (openInterestLimitRatePpm < 0 || openInterestLimitFloorUnits <= 0) {
            throw new IllegalArgumentException("invalid open interest limit configuration");
        }
        long openInterestNotionalUnits = symbolOpenQuantitySteps <= 0 ? 0L : OrderMarginMath.notionalUnits(
                contractType, symbolOpenQuantitySteps, effectivePriceTicks, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits);
        BigInteger scaledOpenInterest = BigInteger.valueOf(openInterestNotionalUnits)
                .multiply(BigInteger.valueOf(openInterestLimitRatePpm))
                .divide(PPM);
        long dynamicLimit = scaledOpenInterest.max(BigInteger.valueOf(openInterestLimitFloorUnits))
                .longValueExact();
        return Math.min(dynamicLimit, maxPositionNotionalUnits);
    }

    private String accountType(ContractType contractType) {
        return contractType.productLine().accountTypeCode();
    }

    private Optional<RiskBracket> riskBracket(InstrumentResponse instrument, long notionalUnits) {
        return instrument.riskLimitBrackets() == null ? Optional.empty() : instrument.riskLimitBrackets().stream()
                .filter(bracket -> bracket.notionalFloorUnits() <= notionalUnits)
                .max(java.util.Comparator.comparingLong(bracket -> bracket.notionalFloorUnits()))
                .map(bracket -> new RiskBracket(bracket.maxLeveragePpm(), bracket.initialMarginRatePpm(),
                        bracket.notionalCapUnits()));
    }

    private Long nullableLong(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record RiskBracket(long maxLeveragePpm, long initialMarginRatePpm, long notionalCapUnits) {
    }

    private record MarginInputs(ContractType contractType,
                                String asset,
                                long notionalMultiplierUnits,
                                long priceTickUnits,
                                long settleScaleUnits,
                                long instrumentInitialMarginRatePpm,
                                long instrumentMaxLeveragePpm,
                                long maxPositionNotionalUnits,
                                long openInterestLimitRatePpm,
                                long openInterestLimitFloorUnits,
                                Long configuredLeveragePpm,
                                long currentSignedQuantitySteps,
                                long pendingSameSideSteps,
                                long symbolOpenQuantitySteps,
                                Long markTicks) {
    }
}
