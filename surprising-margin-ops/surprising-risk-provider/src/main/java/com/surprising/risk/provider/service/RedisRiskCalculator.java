package com.surprising.risk.provider.service;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.InstrumentSpecEpoch;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CachedRiskGroup;
import com.surprising.risk.provider.model.CachedRiskPosition;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.model.RiskInstrumentSpec;
import com.surprising.risk.provider.repository.RiskRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** 使用 Redis 账户输入和进程内最新标记价格计算完整风险组。 */
@Component
public class RedisRiskCalculator {

    private final LatestMarkPriceCache markPriceCache;
    private final RiskRepository repository;
    private final RiskProperties properties;
    /** 读取走不可变快照；规格变更只通过整体替换发布，避免计算线程看到半成品。 */
    private final AtomicReference<Map<InstrumentKey, RiskInstrumentSpec>> specs =
            new AtomicReference<>(Map.of());

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
        InstrumentKey key = new InstrumentKey(new InstrumentSpecEpoch(
                properties.getKafka().getProductLine(), symbol, version));
        RiskInstrumentSpec cached = specs.get().get(key);
        if (cached != null) {
            return cached;
        }
        RiskInstrumentSpec loaded = repository.riskInstrumentSpec(symbol, version)
                .orElseThrow(() -> new IllegalStateException(
                        "risk instrument spec unavailable for " + symbol + " version " + version));
        specs.updateAndGet(previous -> {
            if (previous.containsKey(key)) {
                return previous;
            }
            Map<InstrumentKey, RiskInstrumentSpec> next = new HashMap<>(previous);
            next.put(key, loaded);
            return Map.copyOf(next);
        });
        return loaded;
    }

    public int cachedSpecCount() {
        return specs.get().size();
    }

    private record InstrumentKey(InstrumentSpecEpoch epoch) {
    }
}
