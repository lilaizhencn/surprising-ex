package com.surprising.trading.order.repository;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.SpotReservationRequirement;
import com.surprising.trading.order.service.OrderMarginMath;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SpotOrderReservationRepository {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    private final JdbcTemplate jdbcTemplate;
    private final OrderRepository orderRepository;

    public SpotOrderReservationRepository(JdbcTemplate jdbcTemplate, OrderRepository orderRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderRepository = orderRepository;
    }

    public Optional<SpotReservationRequirement> requirement(String symbol,
                                                            long instrumentVersion,
                                                            OrderSide side,
                                                            OrderType orderType,
                                                            long priceTicks,
                                                            long quantitySteps,
                                                            long marketMaxSlippagePpm,
                                                            long marketMaxMarkAgeMs,
                                                            OrderFeeSnapshot feeSnapshot) {
        return jdbcTemplate.query("""
                SELECT i.base_asset,
                       i.quote_asset,
                       i.quantity_step_units,
                       i.notional_multiplier_units,
                       pm.mark_ticks
                  FROM instruments i
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
                   AND i.instrument_type = 'SPOT'
                   AND i.contract_type = 'SPOT'
                   AND (? <> 'MARKET' OR pm.mark_ticks IS NOT NULL)
                """, (rs, rowNum) -> {
            long markTicks = rs.getLong("mark_ticks");
            Long nullableMarkTicks = rs.wasNull() ? null : markTicks;
            if (side == OrderSide.SELL) {
                long baseUnits = multiplyToLong(quantitySteps, rs.getLong("quantity_step_units"));
                return new SpotReservationRequirement(rs.getString("base_asset"), baseUnits);
            }
            long effectivePriceTicks = orderType == OrderType.MARKET
                    ? OrderMarginMath.upperBoundPriceTicks(orderType, priceTicks, nullableMarkTicks,
                    marketMaxSlippagePpm)
                    : priceTicks;
            long notionalUnits = multiplyToLong(effectivePriceTicks, quantitySteps,
                    rs.getLong("notional_multiplier_units"));
            long feeUnits = feeUnits(notionalUnits, feeSnapshot);
            return new SpotReservationRequirement(rs.getString("quote_asset"),
                    Math.addExact(notionalUnits, feeUnits));
        }, marketMaxMarkAgeMs, symbol, instrumentVersion, orderType.name()).stream().findFirst();
    }

    public boolean reserve(long userId,
                           String asset,
                           long orderId,
                           String symbol,
                           OrderSide side,
                           long amountUnits,
                           Instant now) {
        if (amountUnits <= 0) {
            throw new IllegalArgumentException("amountUnits must be positive");
        }
        jdbcTemplate.update("""
                INSERT INTO account_product_balances (
                    account_type, user_id, asset, available_units, locked_units, updated_at
                ) VALUES ('SPOT', ?, ?, 0, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        var balance = jdbcTemplate.query("""
                SELECT available_units, locked_units
                  FROM account_product_balances
                 WHERE account_type = 'SPOT'
                   AND user_id = ?
                   AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new long[] {rs.getLong("available_units"), rs.getLong("locked_units")},
                userId, asset).stream().findFirst().orElse(new long[] {0L, 0L});
        if (balance[0] < amountUnits) {
            return false;
        }
        long reservationId = orderRepository.nextSequence("spot-reservation");
        int rows = jdbcTemplate.update("""
                INSERT INTO account_spot_order_reservations (
                    reservation_id, order_id, user_id, symbol, side, asset, reserved_units,
                    settled_units, released_units, status, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 'ACTIVE', 'SPOT_ORDER_LOCK', ?, ?)
                ON CONFLICT (order_id) DO NOTHING
                """, reservationId, orderId, userId, symbol, side.name(), asset, amountUnits,
                Timestamp.from(now), Timestamp.from(now));
        if (rows != 1) {
            throw new IllegalStateException("failed to insert spot reservation for order " + orderId);
        }
        rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = available_units - ?,
                       locked_units = locked_units + ?,
                       updated_at = ?
                 WHERE account_type = 'SPOT'
                   AND user_id = ?
                   AND asset = ?
                   AND available_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("failed to reserve spot balance for order " + orderId);
        }
        return true;
    }

    private long feeUnits(long notionalUnits, OrderFeeSnapshot feeSnapshot) {
        long feeRatePpm = Math.max(0L, Math.max(feeSnapshot.makerFeeRatePpm(), feeSnapshot.takerFeeRatePpm()));
        if (feeRatePpm == 0L) {
            return 0L;
        }
        BigInteger numerator = BigInteger.valueOf(notionalUnits).multiply(BigInteger.valueOf(feeRatePpm));
        return divideCeiling(numerator, PPM);
    }

    private long multiplyToLong(long... values) {
        BigInteger product = BigInteger.ONE;
        for (long value : values) {
            if (value <= 0) {
                throw new IllegalArgumentException("spot reservation inputs must be positive");
            }
            product = product.multiply(BigInteger.valueOf(value));
        }
        return product.longValueExact();
    }

    private long divideCeiling(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() <= 0 || numerator.signum() < 0) {
            throw new IllegalArgumentException("positive numerator and denominator are required");
        }
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
        return (quotientAndRemainder[1].signum() == 0
                ? quotientAndRemainder[0]
                : quotientAndRemainder[0].add(BigInteger.ONE)).longValueExact();
    }
}
