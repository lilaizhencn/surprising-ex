package com.surprising.risk.provider.service;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.product.api.InstrumentSpecKey;
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
    private final InstrumentSnapshotCache snapshotCache;
    /** 读取走不可变快照；规格变更只通过整体替换发布，避免计算线程看到半成品。 */
    private final AtomicReference<Map<InstrumentKey, RiskInstrumentSpec>> specs =
            new AtomicReference<>(Map.of());

    public RedisRiskCalculator(LatestMarkPriceCache markPriceCache,
                               RiskRepository repository,
                               RiskProperties properties) {
        this(markPriceCache, repository, properties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RedisRiskCalculator(LatestMarkPriceCache markPriceCache,
                               RiskRepository repository,
                               RiskProperties properties,
                               @org.springframework.beans.factory.annotation.Qualifier("riskInstrumentSnapshotCache")
                               InstrumentSnapshotCache snapshotCache) {
        this.markPriceCache = markPriceCache;
        this.repository = repository;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
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
                    maintenanceMargin, position.positionMarginUnits(), position.positionRevision()));
        }
        return List.copyOf(calculated);
    }

    private RiskInstrumentSpec spec(String symbol, long version) {
        InstrumentKey key = new InstrumentKey(new InstrumentSpecKey(
                properties.getKafka().getProductLine(), symbol, version));
        RiskInstrumentSpec cached = specs.get().get(key);
        if (cached != null) {
            return cached;
        }
        if (snapshotCache != null && snapshotCache.initialized(properties.getKafka().getProductLine())) {
            InstrumentResponse instrument = snapshotCache.version(
                            properties.getKafka().getProductLine(), symbol, version)
                    .orElseThrow(() -> new IllegalStateException(
                            "合约快照不存在: " + symbol + " version " + version));
            long settleScale = snapshotCache.scale(properties.getKafka().getProductLine(), instrument.settleAsset())
                    .orElseThrow(() -> new IllegalStateException(
                            "合约结算资产精度不存在: " + instrument.settleAsset()));
            RiskInstrumentSpec loaded = new RiskInstrumentSpec(
                    instrument.symbol(), instrument.version(), instrument.contractType(), instrument.settleAsset(),
                    instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), settleScale,
                    instrument.maintenanceMarginRatePpm(), instrument.riskLimitBrackets() == null
                            ? List.of()
                            : instrument.riskLimitBrackets().stream()
                            .map(bracket -> new com.surprising.risk.provider.model.RiskMaintenanceBracket(
                                    bracket.notionalFloorUnits(), bracket.maintenanceMarginRatePpm()))
                            .toList());
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
        throw new IllegalStateException("风控合约 JVM 快照尚未就绪");
    }

    public int cachedSpecCount() {
        return specs.get().size();
    }

    private record InstrumentKey(InstrumentSpecKey versionKey) {
    }
}
