package com.surprising.trading.order.repository;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineSql;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.model.MarginRequirement;
import com.surprising.trading.order.service.OrderMarginMath;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderMarginRepository {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);
    private static final String USDT_PERPETUAL = "USDT_PERPETUAL";

    private final JdbcTemplate jdbcTemplate;
    private final OrderRepository orderRepository;

    public OrderMarginRepository(JdbcTemplate jdbcTemplate, OrderRepository orderRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderRepository = orderRepository;
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
                       ss.scale_units AS settle_scale_units,
                       ls.leverage_ppm,
                       COALESCE(p.signed_quantity_steps, 0) AS current_signed_quantity_steps,
                       COALESCE(o.pending_same_side_steps, 0) AS pending_same_side_steps,
                       COALESCE(oi.open_quantity_steps, 0) AS symbol_open_quantity_steps,
                       pm.mark_ticks
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                  JOIN account_asset_scales ss
                    ON ss.asset = i.settle_asset
             LEFT JOIN trading_symbol_open_interest oi
                    ON oi.product_line = %s
                   AND oi.symbol = i.symbol
             LEFT JOIN trading_leverage_settings ls
                    ON ls.user_id = ?
                   AND ls.symbol = i.symbol
                   AND ls.margin_mode = ?
                   AND ls.product_line = %s
             LEFT JOIN account_positions p
                    ON p.user_id = ?
                   AND p.symbol = i.symbol
                   AND p.margin_mode = ?
                   AND p.position_side = ?
                   AND p.product_line = %s
                  LEFT JOIN LATERAL (
                      SELECT COALESCE(SUM(o.remaining_quantity_steps), 0) AS pending_same_side_steps
                        FROM trading_orders o
                       WHERE o.user_id = ?
                         AND o.symbol = i.symbol
                         AND o.product_line = %s
                         AND o.margin_mode = ?
                         AND o.position_side = ?
                         AND o.side = ?
                         AND o.reduce_only = FALSE
                         AND o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                  ) o ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT ((m.mark_price_units + i.price_tick_units / 2) / i.price_tick_units) AS mark_ticks
                        FROM price_mark_ticks m
                       WHERE m.symbol = i.symbol
                         AND m.event_time >= now() - (? * INTERVAL '1 millisecond')
                       ORDER BY m.event_time DESC
                       LIMIT 1
                  ) pm ON TRUE
                 WHERE i.symbol = ?
                   AND i.version = ?
                   AND (? <> 'MARKET' OR pm.mark_ticks IS NOT NULL)
                """.formatted(ProductLineSql.contractTypeProductLineCase("i.contract_type"),
                ProductLineSql.contractTypeProductLineCase("i.contract_type"),
                ProductLineSql.contractTypeProductLineCase("i.contract_type"),
                ProductLineSql.contractTypeProductLineCase("i.contract_type"));
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
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
            // User leverage can be saved at instrument level, but each order must still respect the active risk tier.
            RiskBracket bracket = riskBracket(symbol, instrumentVersion, projectedPositionNotionalUnits)
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
        }, userId, normalizedMarginMode.name(), userId, normalizedMarginMode.name(), normalizedPositionSide.name(),
                userId, normalizedMarginMode.name(), normalizedPositionSide.name(), side.name(),
                marketMaxMarkAgeMs, symbol, instrumentVersion, orderType.name()).stream().findFirst();
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

    public boolean reserve(long userId,
                           String asset,
                           long orderId,
                           String symbol,
                           MarginMode marginMode,
                           long amountUnits,
                           Instant now) {
        return reserve(userId, USDT_PERPETUAL, asset, orderId, symbol, marginMode, PositionSide.NET, amountUnits, now);
    }

    public boolean reserve(long userId,
                           String accountType,
                           String asset,
                           long orderId,
                           String symbol,
                           MarginMode marginMode,
                           long amountUnits,
                           Instant now) {
        return reserve(userId, accountType, asset, orderId, symbol, marginMode, PositionSide.NET, amountUnits, now);
    }

    public boolean reserve(long userId,
                           String accountType,
                           String asset,
                           long orderId,
                           String symbol,
                           MarginMode marginMode,
                           PositionSide positionSide,
                           long amountUnits,
                           Instant now) {
        if (amountUnits <= 0) {
            return true;
        }
        String normalizedAccountType = normalizeMarginAccountType(accountType);
        if (usesProductMarginBalance(normalizedAccountType)) {
            return reserveProductMargin(userId, normalizedAccountType, asset, orderId, symbol, marginMode,
                    positionSide, amountUnits, now);
        }
        return reserveLegacyMargin(userId, normalizedAccountType, asset, orderId, symbol, marginMode, positionSide, amountUnits,
                now);
    }

    private boolean reserveLegacyMargin(long userId,
                                        String accountType,
                                        String asset,
                                        long orderId,
                                        String symbol,
                                        MarginMode marginMode,
                                        PositionSide positionSide,
                                        long amountUnits,
                                        Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        var balance = jdbcTemplate.query("""
                SELECT available_units, locked_units
                  FROM account_balances
                 WHERE user_id = ? AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new long[] {rs.getLong("available_units"), rs.getLong("locked_units")},
                userId, asset).stream().findFirst().orElse(new long[] {0L, 0L});
        if (balance[0] < amountUnits) {
            return false;
        }
        long reservationId = orderRepository.nextSequence("margin-reservation");
        int rows = jdbcTemplate.update("""
                INSERT INTO account_margin_reservations (
                    reservation_id, account_type, user_id, asset, order_id, symbol,
                    margin_mode, position_side, reserved_units, released_units, status, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'ACTIVE', 'ORDER_INITIAL_MARGIN', ?, ?)
                ON CONFLICT (order_id) DO NOTHING
                """, reservationId, accountType, userId, asset, orderId, symbol,
                MarginMode.defaultIfNull(marginMode).name(), PositionSide.defaultIfNull(positionSide).name(), amountUnits,
                Timestamp.from(now), Timestamp.from(now));
        if (rows != 1) {
            throw new IllegalStateException("failed to insert margin reservation for order " + orderId);
        }
        rows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = available_units - ?,
                       locked_units = locked_units + ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                   AND available_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("failed to reserve order margin for order " + orderId);
        }
        return true;
    }

    private boolean reserveProductMargin(long userId,
                                         String accountType,
                                         String asset,
                                     long orderId,
                                     String symbol,
                                     MarginMode marginMode,
                                     PositionSide positionSide,
                                     long amountUnits,
                                     Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_product_balances (
                    account_type, user_id, asset, available_units, locked_units, updated_at
                ) VALUES (?, ?, ?, 0, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType, userId, asset, Timestamp.from(now));
        var balance = jdbcTemplate.query("""
                SELECT available_units, locked_units
                  FROM account_product_balances
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new long[] {rs.getLong("available_units"), rs.getLong("locked_units")},
                accountType, userId, asset).stream().findFirst().orElse(new long[] {0L, 0L});
        if (balance[0] < amountUnits) {
            return false;
        }
        long reservationId = orderRepository.nextSequence("margin-reservation");
        int rows = jdbcTemplate.update("""
                INSERT INTO account_margin_reservations (
                    reservation_id, account_type, user_id, asset, order_id, symbol,
                    margin_mode, position_side, reserved_units, released_units, status, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'ACTIVE', 'ORDER_INITIAL_MARGIN', ?, ?)
                ON CONFLICT (order_id) DO NOTHING
                """, reservationId, accountType, userId, asset, orderId, symbol,
                MarginMode.defaultIfNull(marginMode).name(), PositionSide.defaultIfNull(positionSide).name(), amountUnits,
                Timestamp.from(now), Timestamp.from(now));
        if (rows != 1) {
            throw new IllegalStateException("failed to insert margin reservation for order " + orderId);
        }
        rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = available_units - ?,
                       locked_units = locked_units + ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                   AND available_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), accountType, userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("failed to reserve order margin for order " + orderId);
        }
        return true;
    }

    private String accountType(ContractType contractType) {
        return contractType.productLine().accountTypeCode();
    }

    private String normalizeMarginAccountType(String accountType) {
        if (accountType == null || accountType.isBlank()) {
            return USDT_PERPETUAL;
        }
        String normalized = accountType.trim().toUpperCase();
        ProductLine productLine = ProductLine.requireAccountTypeCode(normalized);
        if (!productLine.isMarginProduct()) {
            throw new IllegalArgumentException("invalid margin account type: " + accountType);
        }
        return normalized;
    }

    private boolean usesProductMarginBalance(String normalizedAccountType) {
        return !USDT_PERPETUAL.equals(normalizedAccountType);
    }

    private Optional<RiskBracket> riskBracket(String symbol, long instrumentVersion, long notionalUnits) {
        return jdbcTemplate.query("""
                SELECT max_leverage_ppm, initial_margin_rate_ppm, notional_cap_units
                  FROM instrument_risk_brackets
                 WHERE symbol = ?
                   AND version = ?
                   AND notional_floor_units <= ?
                 ORDER BY notional_floor_units DESC
                 LIMIT 1
                """, (rs, rowNum) -> new RiskBracket(
                rs.getLong("max_leverage_ppm"),
                rs.getLong("initial_margin_rate_ppm"),
                rs.getLong("notional_cap_units")), symbol, instrumentVersion, notionalUnits)
                .stream().findFirst();
    }

    private Long nullableLong(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record RiskBracket(long maxLeveragePpm, long initialMarginRatePpm, long notionalCapUnits) {
    }
}
