package com.surprising.instrument.provider.service;

import com.surprising.asset.repository.AssetRepository;
import com.surprising.instrument.api.model.*;
import com.surprising.instrument.provider.repository.*;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Current configuration plus immutable operational audit. No historical configuration lookup. */
@Service
public class InstrumentStorageService {
    private final InstrumentRepository instrumentRepository;
    private final InstrumentChangeLogRepository changeLog;
    private final InstrumentRiskBracketRepository riskBracketRepository;
    private final InstrumentIndexSourceRepository indexSourceRepository;
    private final AssetRepository assetScaleRepository;
    private final ObjectMapper json;

    public InstrumentStorageService(InstrumentRepository instrumentRepository, InstrumentChangeLogRepository changeLog,
            InstrumentRiskBracketRepository riskBracketRepository, InstrumentIndexSourceRepository indexSourceRepository,
            AssetRepository assetScaleRepository, ObjectMapper json) {
        this.instrumentRepository=instrumentRepository; this.changeLog=changeLog;
        this.riskBracketRepository=riskBracketRepository; this.indexSourceRepository=indexSourceRepository;
        this.assetScaleRepository=assetScaleRepository; this.json=json;
    }

    public void lockForUpdate(int instrumentId, ProductLine line) {
        instrumentRepository.lockForUpdate(instrumentId, line);
    }

    @Transactional
    public InstrumentResponse save(String symbol,InstrumentUpsertRequest request,String operator,String reason,Instant now) {
        var line=request.contractType().productLine();
        int instrumentId;
        InstrumentResponse before;
        if (request.instrumentId() == null) {
            instrumentId = instrumentRepository.nextInstrumentId();
            before = null;
        } else {
            instrumentId = request.instrumentId();
            instrumentRepository.lockForUpdate(instrumentId, line);
            before = latest(instrumentId, line).orElseThrow();
            requireUnchangedContractTerms(before, request);
        }
        for (int assetId : new java.util.TreeSet<>(java.util.List.of(request.baseAssetId(), request.quoteAssetId(),
                request.settleAssetId(), request.contractValueAssetId()))) {
            var asset = assetScaleRepository.lock(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("instrument asset not found: " + assetId));
            if ((before == null || (before.status() != InstrumentStatus.TRADING && request.status() == InstrumentStatus.TRADING))
                    && (!asset.listed() || !asset.tradingEnabled())) {
                throw new IllegalArgumentException("new instrument requires listed and trading-enabled assets");
            }
        }
        if (request.instrumentType() == com.surprising.instrument.api.model.InstrumentType.OPTION) {
            var underlying = instrumentRepository.current(com.surprising.product.api.InstrumentIds.parse(request.underlyingInstrumentId()), request.underlyingProductLine())
                    .orElseThrow(() -> new IllegalArgumentException("underlying instrument not found in product line"));
            if (underlying.instrumentType() == com.surprising.instrument.api.model.InstrumentType.OPTION
                    || underlying.settleAssetId() != request.settleAssetId()) {
                throw new IllegalArgumentException("option underlying must use the same settlement asset and cannot be an option");
            }
        }
        long changeId=changeLog.nextId();
        long calculationId = before != null && sameCalculation(before, request) ? before.changeId() : changeId;
        instrumentRepository.saveCurrent(instrumentId,symbol,calculationId,changeId,request,now);
        riskBracketRepository.delete(line,instrumentId);
        riskBracketRepository.insertBatch(line,instrumentId,request.riskLimitBrackets());
        indexSourceRepository.delete(line,instrumentId);
        indexSourceRepository.insertBatch(line,instrumentId,request.indexSources());
        var after=latest(instrumentId,line).orElseThrow();
        changeLog.append(line,instrumentId,symbol,changeId,operator,reason,now,
                before==null?null:auditValues(before),auditValues(after));
        return after;
    }

    /** Existing orders and positions retain the units and contract terms under which they were created. */
    private void requireUnchangedContractTerms(InstrumentResponse before, InstrumentUpsertRequest after) {
        requireUnchanged("instrumentType", before.instrumentType(), after.instrumentType());
        requireUnchanged("contractType", before.contractType(), after.contractType());
        requireUnchanged("baseAsset", before.baseAssetId(), after.baseAssetId());
        requireUnchanged("quoteAsset", before.quoteAssetId(), after.quoteAssetId());
        requireUnchanged("settleAsset", before.settleAssetId(), after.settleAssetId());
        requireUnchanged("contractMultiplierPpm", before.contractMultiplierPpm(), after.contractMultiplierPpm());
        requireUnchanged("contractValueAsset", before.contractValueAssetId(), after.contractValueAssetId());
        requireUnchanged("priceTickUnits", before.priceTickUnits(), after.priceTickUnits());
        requireUnchanged("quantityStepUnits", before.quantityStepUnits(), after.quantityStepUnits());
        requireUnchanged("notionalMultiplierUnits", before.notionalMultiplierUnits(), after.notionalMultiplierUnits());
        requireUnchanged("expiryTime", before.expiryTime(), after.expiryTime());
        requireUnchanged("deliveryTime", before.deliveryTime(), after.deliveryTime());
        requireUnchanged("underlyingInstrumentId", before.underlyingInstrumentId(), after.underlyingInstrumentId());
        requireUnchanged("underlyingProductLine", before.underlyingProductLine(), after.underlyingProductLine());
        requireUnchanged("strikePriceUnits", before.strikePriceUnits(), after.strikePriceUnits());
        requireUnchanged("optionType", before.optionType(), after.optionType());
        requireUnchanged("optionExerciseStyle", before.optionExerciseStyle(), after.optionExerciseStyle());
        requireUnchanged("settlementMethod", before.settlementMethod(), after.settlementMethod());
    }

    private void requireUnchanged(String field, Object before, Object after) {
        if (!java.util.Objects.equals(before, after)) {
            throw new IllegalArgumentException("existing instrument contract term cannot change: " + field);
        }
    }

    private boolean sameCalculation(InstrumentResponse before, InstrumentUpsertRequest after) {
        var left = (tools.jackson.databind.node.ObjectNode) json.valueToTree(before);
        var right = (tools.jackson.databind.node.ObjectNode) json.valueToTree(after);
        var metadata = java.util.List.of("instrumentId", "symbol", "baseAsset", "quoteAsset", "settleAsset", "contractValueAsset", "changeId", "lastChangeId", "status", "effectiveTime", "createdAt", "updatedAt");
        left.remove(metadata); right.remove(metadata);
        return left.equals(right);
    }

    private String auditValues(InstrumentResponse value) {
        var tree=(tools.jackson.databind.node.ObjectNode)json.valueToTree(value);
        tree.remove(java.util.List.of("changeId", "lastChangeId"));
        var scales = assetScales();
        Long baseScale = scales.get(value.baseAsset());
        Long quoteScale = scales.get(value.quoteAsset());
        if (baseScale == null || quoteScale == null) {
            throw new IllegalArgumentException("instrument asset scales are required");
        }
        tree.put("baseScaleUnits", baseScale);
        tree.put("quoteScaleUnits", quoteScale);
        return json.writeValueAsString(tree);
    }

    public List<InstrumentChangeLogRepository.Entry> changes(ProductLine line,int instrumentId,long beforeId,int limit) {
        return changeLog.list(line,instrumentId,beforeId,limit);
    }
    public Optional<InstrumentResponse> firstTrading(ProductLine line) {
        return instrumentRepository.firstTradingId(line).flatMap(id -> latest(id, line));
    }

    public Optional<InstrumentResponse> latest(int instrumentId, ProductLine line) {
        return instrumentRepository.current(instrumentId,line).map(this::enrich);
    }
    public List<InstrumentResponse> list(InstrumentType type,InstrumentStatus status) { return list(null,type,status); }
    public List<InstrumentResponse> list(ProductLine line,InstrumentType type,InstrumentStatus status) {
        return enrich(instrumentRepository.list(line,type,status));
    }
    public InstrumentRepository.InstrumentPage listPage(InstrumentType type,InstrumentStatus status,int limit,String cursor,String sort) {
        return listPage(null,type,status,limit,cursor,sort);
    }
    public InstrumentRepository.InstrumentPage listPage(ProductLine line,InstrumentType type,InstrumentStatus status,int limit,String cursor,String sort) {
        return enrich(instrumentRepository.listPage(line,type,status,limit,cursor,sort));
    }
    public List<InstrumentResponse> expiringContractsDue(Instant now,int limit) {
        return enrich(instrumentRepository.expiringContractsDue(null,now,limit));
    }
    public List<InstrumentResponse> settlingContractsDue(Instant now,int limit) {
        return enrich(instrumentRepository.settlingContractsDue(null,now,limit));
    }

    public InstrumentTradeEncoding tradeEncoding(ProductLine line,int instrumentId,long id) { return changeLog.tradeEncoding(line,instrumentId,id); }

    public Map<String, Long> assetScales() {
        return assetScaleRepository == null ? Map.of() : assetScaleRepository.findAll();
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
        List<InstrumentKey> keys = instruments.stream()
                .map(instrument -> new InstrumentKey(instrument.contractType().productLine(), instrument.instrumentId()))
                .toList();
        Map<InstrumentKey, List<RiskLimitBracket>> brackets = riskBracketRepository.findAll(keys);
        Map<InstrumentKey, List<IndexSourceConfig>> sources = indexSourceRepository.findAll(keys);
        List<InstrumentResponse> enriched = instruments.stream()
                .map(instrument -> withDetails(instrument,
                        brackets.getOrDefault(key(instrument), List.of()),
                        sources.getOrDefault(key(instrument), List.of())))
                .toList();
        return enriched;
    }

    private InstrumentKey key(InstrumentResponse instrument) {
        return new InstrumentKey(instrument.contractType().productLine(), instrument.instrumentId());
    }

    private InstrumentResponse withDetails(InstrumentResponse value,
                                           List<RiskLimitBracket> brackets,
                                           List<IndexSourceConfig> sources) {
        return new InstrumentResponse(
                value.instrumentId(), value.baseAssetId(), value.quoteAssetId(), value.settleAssetId(), value.contractValueAssetId(), value.symbol(), value.changeId(), value.instrumentType(), value.contractType(),
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
                value.expiryTime(), value.deliveryTime(), value.underlyingInstrumentId(), value.underlyingProductLine(), value.strikePriceUnits(),
                value.optionType(), value.optionExerciseStyle(), value.settlementMethod(), value.status(),
                value.effectiveTime(), value.createdAt(), value.updatedAt(), brackets, sources, value.lastChangeId());
    }
}
