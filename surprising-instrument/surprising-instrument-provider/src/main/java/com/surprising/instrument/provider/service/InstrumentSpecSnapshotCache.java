package com.surprising.instrument.provider.service;

import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.RiskLimitBracket;
import com.surprising.product.api.InstrumentSpecId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 进程内合约规格快照。
 *
 * <p>快照只在完整规格组装完成后替换，读取方不会看到只有主表字段而缺少风险档位或指数源的半成品。
 * 数据库仍负责启动预热、恢复和最终持久化。</p>
 */
@Component
public class InstrumentSpecSnapshotCache {

    private final ConcurrentMap<InstrumentSpecId, InstrumentResponse> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentMap<LegacyKey, InstrumentResponse> bySymbolAndSpec = new ConcurrentHashMap<>();

    public Optional<InstrumentResponse> get(String symbol, long specId) {
        return Optional.ofNullable(bySymbolAndSpec.get(new LegacyKey(normalizeSymbol(symbol), specId)));
    }

    public void put(InstrumentResponse response) {
        if (response == null || response.symbol() == null || response.symbol().isBlank() || response.version() <= 0) {
            return;
        }
        InstrumentResponse immutable = immutableCopy(response);
        snapshots.put(key(immutable), immutable);
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

    private InstrumentSpecId key(InstrumentResponse response) {
        return new InstrumentSpecId(response.contractType().productLine(), response.symbol(), response.version());
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private record LegacyKey(String symbol, long specId) {
    }
}
