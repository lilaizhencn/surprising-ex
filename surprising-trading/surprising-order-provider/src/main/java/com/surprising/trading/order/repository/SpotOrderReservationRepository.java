package com.surprising.trading.order.repository;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.trading.order.config.TradingOrderProperties;
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
    private final TradingOrderProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public SpotOrderReservationRepository(JdbcTemplate jdbcTemplate, OrderRepository orderRepository) {
        this(jdbcTemplate, orderRepository, (symbol, version, maxAge) -> java.util.OptionalLong.empty(),
                new TradingOrderProperties(), null);
    }

    public SpotOrderReservationRepository(JdbcTemplate jdbcTemplate,
                                          OrderRepository orderRepository,
                                          MarkPriceLookup markPriceLookup) {
        this(jdbcTemplate, orderRepository, markPriceLookup, new TradingOrderProperties(), null);
    }

    @Autowired
    public SpotOrderReservationRepository(JdbcTemplate jdbcTemplate,
                                          OrderRepository orderRepository,
                                          MarkPriceLookup markPriceLookup,
                                          TradingOrderProperties properties,
                                          InstrumentSnapshotCache snapshotCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.markPriceLookup = markPriceLookup;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
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
        if (snapshotCache == null || properties == null) {
            return Optional.empty();
        }
        var productLine = properties.getKafka().getProductLine();
        var instrument = snapshotCache.version(productLine, symbol, instrumentVersion)
                .filter(value -> value.instrumentType() == InstrumentType.SPOT
                        && value.contractType() == com.surprising.instrument.api.model.ContractType.SPOT)
                .orElseThrow(() -> new IllegalArgumentException("现货合约快照不存在: " + symbol + "@" + instrumentVersion));
        Long markPriceTicks = markPriceLookup.latestMarkPriceTicks(symbol, instrumentVersion, marketMaxMarkAgeMs)
                .stream().boxed().findFirst().orElse(null);
        if (orderType == OrderType.MARKET && markPriceTicks == null) {
            return Optional.empty();
        }
        if (side == OrderSide.SELL) {
            long baseUnits = multiplyToLong(quantitySteps, instrument.quantityStepUnits());
            return Optional.of(new SpotReservationRequirement(instrument.baseAsset(), baseUnits));
        }
        long effectivePriceTicks = orderType == OrderType.MARKET
                ? OrderMarginMath.upperBoundPriceTicks(orderType, priceTicks, markPriceTicks, marketMaxSlippagePpm)
                : priceTicks;
        long notionalUnits = multiplyToLong(effectivePriceTicks, quantitySteps, instrument.notionalMultiplierUnits());
        long feeUnits = feeUnits(notionalUnits, feeSnapshot);
        return Optional.of(new SpotReservationRequirement(instrument.quoteAsset(), Math.addExact(notionalUnits, feeUnits)));
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
