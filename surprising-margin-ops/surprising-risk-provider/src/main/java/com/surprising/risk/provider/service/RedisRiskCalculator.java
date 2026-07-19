package com.surprising.risk.provider.service;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CachedRiskGroup;
import com.surprising.risk.provider.model.CachedRiskPosition;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.model.RiskInstrumentSpec;
import com.surprising.risk.provider.repository.RiskRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Calculates a complete group from Redis account inputs and the latest in-process mark prices. */
@Component
public class RedisRiskCalculator {

    private final LatestMarkPriceCache markPriceCache;
    private final RiskRepository repository;
    private final RiskProperties properties;
    private final Map<InstrumentKey, RiskInstrumentSpec> specs = new ConcurrentHashMap<>();

    public RedisRiskCalculator(LatestMarkPriceCache markPriceCache,
                               RiskRepository repository,
                               RiskProperties properties) {
        this.markPriceCache = markPriceCache;
        this.repository = repository;
        this.properties = properties;
    }

    public List<CalculatedPositionRisk> calculate(CachedRiskGroup group) {
        if (group == null || group.capturedAt().isBefore(Instant.now().minus(properties.getRedisState().getStateTtl()))) {
            throw new IllegalStateException("Redis risk group is missing or stale");
        }
        List<CalculatedPositionRisk> calculated = new ArrayList<>(group.positions().size());
        for (CachedRiskPosition position : group.positions()) {
            RiskInstrumentSpec spec = spec(position.symbol(), position.instrumentVersion());
            MarkPriceEvent mark = markPriceCache.fresh(position.symbol(), properties.getCalculation().getMaxMarkAge())
                    .filter(value -> value.instrumentVersion() == position.instrumentVersion())
                    .orElseThrow(() -> new IllegalStateException(
                            "fresh mark price unavailable for " + position.symbol()));
            long notional = RiskMath.notionalUnits(
                    spec.contractType(), position.signedQuantitySteps(), mark.markPriceTicks(),
                    spec.notionalMultiplierUnits(), spec.priceTickUnits(), spec.settleScaleUnits());
            long maintenanceRate = spec.maintenanceMarginRatePpm(notional);
            long unrealizedPnl = RiskMath.unrealizedPnlUnits(
                    spec.contractType(), position.signedQuantitySteps(), position.entryPriceTicks(),
                    mark.markPriceTicks(), spec.notionalMultiplierUnits(), spec.priceTickUnits(),
                    spec.settleScaleUnits());
            long maintenanceMargin = RiskMath.maintenanceMarginUnits(
                    spec.contractType(), position.signedQuantitySteps(), mark.markPriceTicks(),
                    spec.notionalMultiplierUnits(), spec.priceTickUnits(), spec.settleScaleUnits(), maintenanceRate);
            calculated.add(new CalculatedPositionRisk(
                    group.key().userId(), position.symbol(), position.marginMode(), position.positionSide(),
                    position.instrumentVersion(), position.settleAsset(), position.signedQuantitySteps(),
                    position.entryPriceTicks(), mark.markPriceTicks(), notional, unrealizedPnl,
                    maintenanceMargin, position.positionMarginUnits()));
        }
        return List.copyOf(calculated);
    }

    private RiskInstrumentSpec spec(String symbol, long version) {
        InstrumentKey key = new InstrumentKey(symbol, version);
        return specs.computeIfAbsent(key, ignored -> repository.riskInstrumentSpec(symbol, version)
                .orElseThrow(() -> new IllegalStateException(
                        "risk instrument spec unavailable for " + symbol + " version " + version)));
    }

    private record InstrumentKey(String symbol, long version) {
    }
}
