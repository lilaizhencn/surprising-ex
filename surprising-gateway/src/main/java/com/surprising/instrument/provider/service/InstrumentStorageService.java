package com.surprising.instrument.provider.service;

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
    private final InstrumentAssetScaleRepository assetScaleRepository;
    private final ObjectMapper json;

    public InstrumentStorageService(InstrumentRepository instrumentRepository, InstrumentChangeLogRepository changeLog,
            InstrumentRiskBracketRepository riskBracketRepository, InstrumentIndexSourceRepository indexSourceRepository,
            InstrumentAssetScaleRepository assetScaleRepository, ObjectMapper json) {
        this.instrumentRepository=instrumentRepository; this.changeLog=changeLog;
        this.riskBracketRepository=riskBracketRepository; this.indexSourceRepository=indexSourceRepository;
        this.assetScaleRepository=assetScaleRepository; this.json=json;
    }

    public void lockForUpdate(String symbol) { changeLog.lockSymbol(symbol, Instant.now()); }

    @Transactional
    public InstrumentResponse save(String symbol,InstrumentUpsertRequest request,String operator,String reason,Instant now) {
        var line=request.contractType().productLine();
        changeLog.lockSymbol(symbol,now);
        var before=latest(symbol,line).orElse(null);
        long changeId=changeLog.nextId();
        long calculationId = before != null && sameCalculation(before, request) ? before.changeId() : changeId;
        instrumentRepository.saveCurrent(symbol,calculationId,changeId,request,now);
        riskBracketRepository.delete(line,symbol);
        riskBracketRepository.insertBatch(line,symbol,request.riskLimitBrackets());
        indexSourceRepository.delete(line,symbol);
        indexSourceRepository.insertBatch(line,symbol,request.indexSources());
        var after=latest(symbol,line).orElseThrow();
        changeLog.append(line,symbol,changeId,operator,reason,now,
                before==null?null:auditValues(before),auditValues(after));
        return after;
    }

    private boolean sameCalculation(InstrumentResponse before, InstrumentUpsertRequest after) {
        var left = (tools.jackson.databind.node.ObjectNode) json.valueToTree(before);
        var right = (tools.jackson.databind.node.ObjectNode) json.valueToTree(after);
        var metadata = java.util.List.of("changeId", "lastChangeId", "status", "effectiveTime", "createdAt", "updatedAt");
        left.remove(metadata); right.remove(metadata);
        return left.equals(right);
    }

    private String auditValues(InstrumentResponse value) {
        var tree=(tools.jackson.databind.node.ObjectNode)json.valueToTree(value);
        tree.remove(java.util.List.of("changeId", "lastChangeId"));
        return json.writeValueAsString(tree);
    }

    public List<InstrumentChangeLogRepository.Entry> changes(ProductLine line,String symbol,long beforeId,int limit) {
        return changeLog.list(line,symbol,beforeId,limit);
    }
    public Optional<InstrumentResponse> latest(String symbol) { return latest(symbol,null); }
    public Optional<InstrumentResponse> latest(String symbol,ProductLine line) {
        return instrumentRepository.current(symbol,line).map(this::enrich);
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

    public InstrumentTradeEncoding tradeEncoding(ProductLine line,String symbol,long id) { return changeLog.tradeEncoding(line,symbol,id); }

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
                .map(instrument -> new InstrumentKey(instrument.contractType().productLine(), instrument.symbol()))
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
        return new InstrumentKey(instrument.contractType().productLine(), instrument.symbol());
    }

    private InstrumentResponse withDetails(InstrumentResponse value,
                                           List<RiskLimitBracket> brackets,
                                           List<IndexSourceConfig> sources) {
        return new InstrumentResponse(
                value.symbol(), value.changeId(), value.instrumentType(), value.contractType(),
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
                value.effectiveTime(), value.createdAt(), value.updatedAt(), brackets, sources, value.lastChangeId());
    }
}
