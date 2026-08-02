package com.surprising.trading.order.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.SpotReservationRequirement;
import java.math.BigInteger;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 现货冻结金额计算器。
 *
 * <p>这里只读取合约 JVM 快照并执行纯计算，不是数据库 Repository。现货限价单使用订单自身
 * 价格计算冻结金额；现货市价单在接入订单簿保护价前必须拒绝，不能借用衍生品标记价。</p>
 */
@Service
public class SpotReservationCalculator {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    private final TradingOrderProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    @Autowired
    public SpotReservationCalculator(TradingOrderProperties properties,
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
        ProductLine productLine = properties.getKafka().getProductLine();
        var instrument = snapshotCache.version(productLine, symbol, instrumentVersion)
                .filter(value -> value.instrumentType() == InstrumentType.SPOT
                        && value.contractType() == ContractType.SPOT)
                .orElseThrow(() -> new IllegalArgumentException("现货合约快照不存在: " + symbol + "@" + instrumentVersion));
        if (orderType == OrderType.MARKET) {
            return Optional.empty();
        }
        if (side == OrderSide.SELL) {
            long baseUnits = multiplyToLong(quantitySteps, instrument.quantityStepUnits());
            return Optional.of(new SpotReservationRequirement(instrument.baseAsset(), baseUnits));
        }
        long notionalUnits = multiplyToLong(priceTicks, quantitySteps, instrument.notionalMultiplierUnits());
        long feeUnits = feeUnits(notionalUnits, feeSnapshot);
        return Optional.of(new SpotReservationRequirement(instrument.quoteAsset(),
                Math.addExact(notionalUnits, feeUnits)));
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
            if (value <= 0L) {
                throw new IllegalArgumentException("现货冻结金额参数必须为正数");
            }
            product = product.multiply(BigInteger.valueOf(value));
        }
        return product.longValueExact();
    }

    private long divideCeiling(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() <= 0 || numerator.signum() < 0) {
            throw new IllegalArgumentException("分子和分母必须为非负且分母为正");
        }
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
        return (quotientAndRemainder[1].signum() == 0
                ? quotientAndRemainder[0]
                : quotientAndRemainder[0].add(BigInteger.ONE)).longValueExact();
    }
}
