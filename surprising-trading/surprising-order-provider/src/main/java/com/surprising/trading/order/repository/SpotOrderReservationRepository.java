package com.surprising.trading.order.repository;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.SpotReservationRequirement;
import java.math.BigInteger;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

@Repository
public class SpotOrderReservationRepository {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    private final TradingOrderProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    @Autowired
    public SpotOrderReservationRepository(TradingOrderProperties properties,
                                          @Qualifier("orderInstrumentSnapshotCache") InstrumentSnapshotCache snapshotCache) {
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    public Optional<SpotReservationRequirement> requirement(String symbol,
                                                            long instrumentVersion,
                                                            OrderSide side,
                                                            OrderType orderType,
                                                            long priceTicks,
                                                            long quantitySteps,
                                                            OrderFeeSnapshot feeSnapshot) {
        if (snapshotCache == null || properties == null) {
            return Optional.empty();
        }
        var productLine = properties.getKafka().getProductLine();
        var instrument = snapshotCache.version(productLine, symbol, instrumentVersion)
                .filter(value -> value.instrumentType() == InstrumentType.SPOT
                        && value.contractType() == com.surprising.instrument.api.model.ContractType.SPOT)
                .orElseThrow(() -> new IllegalArgumentException("现货合约快照不存在: " + symbol + "@" + instrumentVersion));
        // 现货市价单尚未接入订单簿保护价，禁止用标记价估算冻结金额。
        if (orderType == OrderType.MARKET) {
            return Optional.empty();
        }
        if (side == OrderSide.SELL) {
            long baseUnits = multiplyToLong(quantitySteps, instrument.quantityStepUnits());
            return Optional.of(new SpotReservationRequirement(instrument.baseAsset(), baseUnits));
        }
        long notionalUnits = multiplyToLong(priceTicks, quantitySteps, instrument.notionalMultiplierUnits());
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
