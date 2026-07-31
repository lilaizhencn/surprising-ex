package com.surprising.instrument.provider.service;

import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.api.model.RiskLimitBracket;
import com.surprising.instrument.provider.repository.InstrumentCurrentVersionRepository;
import com.surprising.instrument.provider.repository.InstrumentIndexSourceRepository;
import com.surprising.instrument.provider.repository.InstrumentProductCurrentVersionRepository;
import com.surprising.instrument.provider.repository.InstrumentRepository;
import com.surprising.instrument.provider.repository.InstrumentRiskBracketRepository;
import com.surprising.instrument.provider.repository.InstrumentSequenceRepository;
import com.surprising.instrument.provider.repository.InstrumentVersionKey;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 聚合合约主表、当前版本指针、产品线指针、风险档位和指数源。
 *
 * <p>各 Repository 只访问一张物理表；本服务负责组合完整快照，避免查询层依赖跨表 Repository。</p>
 */
@Service
public class InstrumentStorageService {

    private static final Set<ProductLine> EXPIRING_PRODUCT_LINES = Set.of(
            ProductLine.LINEAR_DELIVERY, ProductLine.INVERSE_DELIVERY, ProductLine.OPTION);

    private final InstrumentRepository instrumentRepository;
    private final InstrumentSequenceRepository sequenceRepository;
    private final InstrumentCurrentVersionRepository currentVersionRepository;
    private final InstrumentProductCurrentVersionRepository productCurrentVersionRepository;
    private final InstrumentRiskBracketRepository riskBracketRepository;
    private final InstrumentIndexSourceRepository indexSourceRepository;

    public InstrumentStorageService(InstrumentRepository instrumentRepository,
                                    InstrumentSequenceRepository sequenceRepository,
                                    InstrumentCurrentVersionRepository currentVersionRepository,
                                    InstrumentProductCurrentVersionRepository productCurrentVersionRepository,
                                    InstrumentRiskBracketRepository riskBracketRepository,
                                    InstrumentIndexSourceRepository indexSourceRepository) {
        this.instrumentRepository = instrumentRepository;
        this.sequenceRepository = sequenceRepository;
        this.currentVersionRepository = currentVersionRepository;
        this.productCurrentVersionRepository = productCurrentVersionRepository;
        this.riskBracketRepository = riskBracketRepository;
        this.indexSourceRepository = indexSourceRepository;
    }

    public long nextVersion(String symbol) {
        long initialVersion = instrumentRepository.maxVersion(symbol) + 1L;
        return sequenceRepository.next(symbol, initialVersion);
    }

    public void insert(String symbol, long version, InstrumentUpsertRequest request, Instant now) {
        instrumentRepository.insert(symbol, version, request, now);
        riskBracketRepository.insertBatch(symbol, version, request.riskLimitBrackets());
        indexSourceRepository.insertBatch(symbol, version, request.indexSources());
    }

    public void setCurrentVersion(String symbol, long version, Instant now) {
        currentVersionRepository.set(symbol, version, now);
    }

    public void setCurrentVersion(ProductLine productLine, String symbol, long version, Instant now) {
        productCurrentVersionRepository.set(productLine, symbol, version, now);
    }

    public Optional<InstrumentResponse> latest(String symbol) {
        OptionalLong version = currentVersionRepository.findVersion(symbol);
        return version.isEmpty() ? Optional.empty() : version(symbol, version.getAsLong());
    }

    public Optional<InstrumentResponse> latest(String symbol, ProductLine productLine) {
        OptionalLong version = productCurrentVersionRepository.findVersion(productLine, symbol);
        return version.isEmpty() ? Optional.empty() : version(symbol, version.getAsLong());
    }

    public Optional<InstrumentResponse> version(String symbol, long version) {
        return instrumentRepository.version(symbol, version).map(this::enrich);
    }

    public List<InstrumentResponse> list(InstrumentType type, InstrumentStatus status) {
        return enrich(instrumentRepository.list(currentVersionRepository.findAll(), type, status));
    }

    public List<InstrumentResponse> list(ProductLine productLine,
                                         InstrumentType type,
                                         InstrumentStatus status) {
        if (productLine == null) {
            return list(type, status);
        }
        return enrich(instrumentRepository.list(productCurrentVersionRepository.findAll(productLine), type, status));
    }

    public InstrumentRepository.InstrumentPage listPage(InstrumentType type,
                                                        InstrumentStatus status,
                                                        int limit,
                                                        String cursor,
                                                        String sort) {
        return enrich(instrumentRepository.listPage(
                currentVersionRepository.findAll(), type, status, limit, cursor, sort));
    }

    public InstrumentRepository.InstrumentPage listPage(ProductLine productLine,
                                                        InstrumentType type,
                                                        InstrumentStatus status,
                                                        int limit,
                                                        String cursor,
                                                        String sort) {
        if (productLine == null) {
            return listPage(type, status, limit, cursor, sort);
        }
        return enrich(instrumentRepository.listPage(
                productCurrentVersionRepository.findAll(productLine), type, status, limit, cursor, sort));
    }

    public InstrumentRepository.InstrumentPage versionsPage(String symbol,
                                                            ProductLine productLine,
                                                            int limit,
                                                            String cursor,
                                                            String sort) {
        return enrich(instrumentRepository.versionsPage(symbol, productLine, limit, cursor, sort));
    }

    public List<InstrumentResponse> expiringContractsDue(Instant now, int limit) {
        List<InstrumentVersionKey> keys = productCurrentVersionRepository.findAll(EXPIRING_PRODUCT_LINES);
        return enrich(instrumentRepository.expiringContractsDue(keys, now, limit));
    }

    public List<InstrumentResponse> settlingContractsDue(Instant now, int limit) {
        List<InstrumentVersionKey> keys = productCurrentVersionRepository.findAll(EXPIRING_PRODUCT_LINES);
        return enrich(instrumentRepository.settlingContractsDue(keys, now, limit));
    }

    private InstrumentRepository.InstrumentPage enrich(InstrumentRepository.InstrumentPage page) {
        return new InstrumentRepository.InstrumentPage(
                enrich(page.instruments()), page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    private InstrumentResponse enrich(InstrumentResponse instrument) {
        return enrich(List.of(instrument)).getFirst();
    }

    private List<InstrumentResponse> enrich(List<InstrumentResponse> instruments) {
        if (instruments.isEmpty()) {
            return List.of();
        }
        List<InstrumentVersionKey> keys = instruments.stream()
                .map(instrument -> new InstrumentVersionKey(instrument.symbol(), instrument.version()))
                .toList();
        Map<InstrumentVersionKey, List<RiskLimitBracket>> brackets = riskBracketRepository.findAll(keys);
        Map<InstrumentVersionKey, List<IndexSourceConfig>> sources = indexSourceRepository.findAll(keys);
        return instruments.stream()
                .map(instrument -> withDetails(instrument,
                        brackets.getOrDefault(key(instrument), List.of()),
                        sources.getOrDefault(key(instrument), List.of())))
                .toList();
    }

    private InstrumentVersionKey key(InstrumentResponse instrument) {
        return new InstrumentVersionKey(instrument.symbol(), instrument.version());
    }

    private InstrumentResponse withDetails(InstrumentResponse value,
                                           List<RiskLimitBracket> brackets,
                                           List<IndexSourceConfig> sources) {
        return new InstrumentResponse(
                value.symbol(), value.version(), value.instrumentType(), value.contractType(),
                value.baseAsset(), value.quoteAsset(), value.settleAsset(), value.contractMultiplierPpm(),
                value.contractValueAsset(), value.priceTickUnits(), value.quantityStepUnits(),
                value.minQuantitySteps(), value.maxQuantitySteps(), value.minNotionalUnits(),
                value.maxNotionalUnits(), value.notionalMultiplierUnits(), value.pricePrecision(),
                value.quantityPrecision(), value.supportedOrderTypes(), value.supportedTimeInForce(),
                value.postOnlyEnabled(), value.reduceOnlyEnabled(), value.marketOrderEnabled(),
                value.maxLeveragePpm(), value.initialMarginRatePpm(), value.maintenanceMarginRatePpm(),
                value.makerFeeRatePpm(), value.takerFeeRatePpm(), value.maxPositionNotionalUnits(),
                value.userOpenInterestLimitRatePpm(), value.userOpenInterestLimitFloorUnits(),
                value.fundingIntervalHours(), value.interestRatePpm(), value.fundingRateCapPpm(),
                value.fundingRateFloorPpm(), value.impactNotionalUnits(), value.minValidIndexSources(),
                value.expiryTime(), value.deliveryTime(), value.underlyingSymbol(), value.strikePriceUnits(),
                value.optionType(), value.optionExerciseStyle(), value.settlementMethod(), value.status(),
                value.effectiveTime(), value.createdAt(), value.updatedAt(), brackets, sources);
    }
}
