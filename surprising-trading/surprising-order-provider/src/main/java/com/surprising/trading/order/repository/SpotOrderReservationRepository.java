package com.surprising.trading.order.repository;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.MarkPriceLookup;
import com.surprising.trading.order.model.SpotReservationRequirement;
import com.surprising.trading.order.service.OrderMarginMath;
import java.math.BigInteger;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
public class SpotOrderReservationRepository {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    private final JdbcTemplate jdbcTemplate;
    private final MarkPriceLookup markPriceLookup;

    public SpotOrderReservationRepository(JdbcTemplate jdbcTemplate, OrderRepository orderRepository) {
        this(jdbcTemplate, orderRepository, (symbol, version, maxAge) -> java.util.OptionalLong.empty());
    }

    @Autowired
    public SpotOrderReservationRepository(JdbcTemplate jdbcTemplate,
                                          OrderRepository orderRepository,
                                          MarkPriceLookup markPriceLookup) {
        this.jdbcTemplate = jdbcTemplate;
        this.markPriceLookup = markPriceLookup;
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
        Long markPriceTicks = markPriceLookup.latestMarkPriceTicks(symbol, instrumentVersion, marketMaxMarkAgeMs)
                .stream().boxed().findFirst().orElse(null);
        return jdbcTemplate.query("""
                SELECT i.base_asset,
                       i.quote_asset,
                       i.quantity_step_units,
                       i.notional_multiplier_units,
                       pm.mark_ticks
                  FROM instruments i
                 CROSS JOIN (SELECT CAST(? AS bigint) AS mark_ticks) pm
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
        }, markPriceTicks, symbol, instrumentVersion, orderType.name()).stream().findFirst();
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
