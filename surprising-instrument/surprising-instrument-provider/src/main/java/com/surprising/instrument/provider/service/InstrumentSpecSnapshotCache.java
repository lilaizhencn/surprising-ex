package com.surprising.instrument.provider.service;

import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.RiskLimitBracket;
import com.surprising.product.api.InstrumentSpecEpoch;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 进程内合约规格快照。
 *
 * <p>快照只在完整规格组装完成后替换，读取方不会看到只有主表字段而缺少风险档位或指数源的半成品。
 * 数据库仍负责启动预热、恢复和最终持久化。</p>
 */
@Component
public class InstrumentSpecSnapshotCache {

    private final ConcurrentMap<InstrumentSpecEpoch, InstrumentResponse> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentMap<LegacyKey, InstrumentResponse> bySymbolAndSpec = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder replacements = new LongAdder();

    public InstrumentSpecSnapshotCache() {
        this(null);
    }

    @Autowired
    public InstrumentSpecSnapshotCache(MeterRegistry meterRegistry) {
        if (meterRegistry != null) {
            Gauge.builder("surprising.instrument.snapshot.entries", snapshots, Map::size)
                    .description("合约规格不可变快照数量")
                    .register(meterRegistry);
            Gauge.builder("surprising.instrument.snapshot.hits", hits, LongAdder::sum)
                    .description("合约规格快照命中次数")
                    .register(meterRegistry);
            Gauge.builder("surprising.instrument.snapshot.misses", misses, LongAdder::sum)
                    .description("合约规格快照未命中次数")
                    .register(meterRegistry);
            Gauge.builder("surprising.instrument.snapshot.replacements", replacements, LongAdder::sum)
                    .description("同一规格代际快照替换次数，用于漂移监控")
                    .register(meterRegistry);
        }
    }

    public Optional<InstrumentResponse> get(String symbol, long specId) {
        InstrumentResponse value = bySymbolAndSpec.get(new LegacyKey(normalizeSymbol(symbol), specId));
        if (value == null) {
            misses.increment();
        } else {
            hits.increment();
        }
        return Optional.ofNullable(value);
    }

    public void put(InstrumentResponse response) {
        if (response == null || response.symbol() == null || response.symbol().isBlank() || response.version() <= 0) {
            return;
        }
        InstrumentResponse immutable = immutableCopy(response);
        InstrumentResponse previous = snapshots.put(key(immutable), immutable);
        if (previous != null && !previous.equals(immutable)) {
            replacements.increment();
        }
        bySymbolAndSpec.put(new LegacyKey(normalizeSymbol(immutable.symbol()), immutable.version()), immutable);
    }

    public int size() {
        return snapshots.size();
    }

    private InstrumentResponse immutableCopy(InstrumentResponse value) {
        List<RiskLimitBracket> brackets = value.riskLimitBrackets() == null
                ? List.of() : List.copyOf(value.riskLimitBrackets());
        List<IndexSourceConfig> sources = value.indexSources() == null
                ? List.of() : List.copyOf(value.indexSources());
        return new InstrumentResponse(
                value.symbol(), value.version(), value.instrumentType(), value.contractType(), value.baseAsset(),
                value.quoteAsset(), value.settleAsset(), value.contractMultiplierPpm(), value.contractValueAsset(),
                value.priceTickUnits(), value.quantityStepUnits(), value.minQuantitySteps(), value.maxQuantitySteps(),
                value.minNotionalUnits(), value.maxNotionalUnits(), value.notionalMultiplierUnits(),
                value.pricePrecision(), value.quantityPrecision(),
                value.supportedOrderTypes() == null ? List.of() : List.copyOf(value.supportedOrderTypes()),
                value.supportedTimeInForce() == null ? List.of() : List.copyOf(value.supportedTimeInForce()),
                value.postOnlyEnabled(), value.reduceOnlyEnabled(),
                value.marketOrderEnabled(), value.maxLeveragePpm(), value.initialMarginRatePpm(),
                value.maintenanceMarginRatePpm(), value.makerFeeRatePpm(), value.takerFeeRatePpm(),
                value.maxPositionNotionalUnits(), value.userOpenInterestLimitRatePpm(),
                value.userOpenInterestLimitFloorUnits(), value.fundingIntervalHours(), value.interestRatePpm(),
                value.fundingRateCapPpm(), value.fundingRateFloorPpm(), value.impactNotionalUnits(),
                value.minValidIndexSources(), value.expiryTime(), value.deliveryTime(), value.underlyingSymbol(),
                value.strikePriceUnits(), value.optionType(), value.optionExerciseStyle(), value.settlementMethod(),
                value.status(), value.effectiveTime(), value.createdAt(), value.updatedAt(), brackets, sources);
    }

    private InstrumentSpecEpoch key(InstrumentResponse response) {
        return new InstrumentSpecEpoch(
                response.contractType().productLine(), response.symbol(), response.version());
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private record LegacyKey(String symbol, long specId) {
    }
}
