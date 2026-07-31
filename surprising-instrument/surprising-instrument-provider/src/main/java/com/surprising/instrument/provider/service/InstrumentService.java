package com.surprising.instrument.provider.service;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentEventType;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import com.surprising.instrument.provider.config.InstrumentProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstrumentService {

    private final InstrumentStorageService storageService;
    private final InstrumentValidator instrumentValidator;
    private final InstrumentProperties properties;
    private final InstrumentOutboxService outboxService;

    public InstrumentService(InstrumentStorageService storageService,
                             InstrumentValidator instrumentValidator,
                             InstrumentProperties properties,
                             InstrumentOutboxService outboxService) {
        this.storageService = storageService;
        this.instrumentValidator = instrumentValidator;
        this.properties = properties;
        this.outboxService = outboxService;
    }

    public InstrumentResponse latest(String symbol) {
        return storageService.latest(normalizeSymbol(symbol))
                .orElseThrow(() -> new IllegalStateException("instrument not found: " + symbol));
    }

    public InstrumentResponse latest(String symbol, ProductLine productLine) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (productLine == null) {
            return latest(normalizedSymbol);
        }
        Optional<InstrumentResponse> productCurrent = storageService.latest(normalizedSymbol, productLine);
        if (productCurrent.isPresent()) {
            InstrumentResponse response = productCurrent.get();
            if (response.contractType().productLine() == productLine) {
                return response;
            }
            throw new IllegalStateException("instrument product current mismatch: "
                    + symbol + ":" + productLine.name() + ":" + response.contractType().name());
        }
        InstrumentResponse response = latest(normalizedSymbol);
        if (response.contractType().productLine() == productLine) {
            return response;
        }
        throw new IllegalStateException("instrument not found for productLine: " + symbol + ":" + productLine.name());
    }

    public InstrumentResponse version(String symbol, long version) {
        return storageService.version(normalizeSymbol(symbol), version)
                .orElseThrow(() -> new IllegalStateException("instrument version not found: " + symbol + ":" + version));
    }

    public InstrumentQueryResponse list(InstrumentType type, InstrumentStatus status) {
        var rows = storageService.list(type, status);
        return new InstrumentQueryResponse(rows.size(), rows);
    }

    public InstrumentQueryResponse list(ProductLine productLine, InstrumentType type, InstrumentStatus status) {
        var rows = storageService.list(productLine, type, status);
        return new InstrumentQueryResponse(rows.size(), rows);
    }

    public InstrumentQueryResponse list(InstrumentType type, InstrumentStatus status, int limit, String cursor, String sort) {
        var page = storageService.listPage(type, status, limit, cursor, sort);
        return new InstrumentQueryResponse(page.instruments().size(), page.instruments(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public InstrumentQueryResponse list(ProductLine productLine,
                                        InstrumentType type,
                                        InstrumentStatus status,
                                        int limit,
                                        String cursor,
                                        String sort) {
        var page = storageService.listPage(productLine, type, status, limit, cursor, sort);
        return new InstrumentQueryResponse(page.instruments().size(), page.instruments(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public InstrumentQueryResponse versions(String symbol, int limit, String cursor, String sort) {
        return versions(symbol, null, limit, cursor, sort);
    }

    public InstrumentQueryResponse versions(String symbol, ProductLine productLine, int limit, String cursor, String sort) {
        var page = storageService.versionsPage(normalizeSymbol(symbol), productLine, limit, cursor, sort);
        return new InstrumentQueryResponse(page.instruments().size(), page.instruments(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    @Transactional
    public InstrumentResponse upsert(InstrumentUpsertRequest request) {
        return upsert(request, InstrumentEventType.UPSERTED);
    }

    private InstrumentResponse upsert(InstrumentUpsertRequest request, InstrumentEventType eventType) {
        instrumentValidator.validate(request);
        String symbol = normalizeSymbol(request.symbol());
        Instant now = Instant.now();
        long version = storageService.nextVersion(symbol);
        storageService.insert(symbol, version, request, now);
        storageService.setCurrentVersion(request.contractType().productLine(), symbol, version, now);
        storageService.setCurrentVersion(symbol, version, now);
        InstrumentResponse response = storageService.version(symbol, version)
                .orElseThrow(() -> new IllegalStateException("instrument insert failed: " + symbol));
        publish(response, eventType);
        return response;
    }

    @Transactional
    public InstrumentResponse updateStatus(String symbol, InstrumentStatus status) {
        return updateStatus(symbol, null, status);
    }

    @Transactional
    public InstrumentResponse updateStatus(String symbol, ProductLine productLine, InstrumentStatus status) {
        InstrumentResponse current = latest(symbol, productLine);
        InstrumentUpsertRequest request = new InstrumentUpsertRequest(
                current.symbol(), current.instrumentType(), current.contractType(), current.baseAsset(),
                current.quoteAsset(), current.settleAsset(), current.contractMultiplierPpm(), current.contractValueAsset(),
                current.priceTickUnits(), current.quantityStepUnits(), current.minQuantitySteps(), current.maxQuantitySteps(),
                current.minNotionalUnits(), current.maxNotionalUnits(), current.notionalMultiplierUnits(),
                current.pricePrecision(), current.quantityPrecision(),
                current.supportedOrderTypes(), current.supportedTimeInForce(), current.postOnlyEnabled(),
                current.reduceOnlyEnabled(), current.marketOrderEnabled(), current.maxLeveragePpm(),
                current.initialMarginRatePpm(), current.maintenanceMarginRatePpm(), current.makerFeeRatePpm(),
                current.takerFeeRatePpm(), current.maxPositionNotionalUnits(),
                current.userOpenInterestLimitRatePpm(), current.userOpenInterestLimitFloorUnits(),
                current.fundingIntervalHours(),
                current.interestRatePpm(), current.fundingRateCapPpm(), current.fundingRateFloorPpm(),
                current.impactNotionalUnits(), current.minValidIndexSources(), current.expiryTime(),
                current.deliveryTime(), current.underlyingSymbol(), current.strikePriceUnits(),
                current.optionType(), current.optionExerciseStyle(), current.settlementMethod(), status, Instant.now(),
                current.riskLimitBrackets(), current.indexSources());
        return upsert(request, InstrumentEventType.STATUS_CHANGED);
    }

    private void publish(InstrumentResponse response, InstrumentEventType eventType) {
        Instant eventTime = Instant.now();
        InstrumentEvent event = new InstrumentEvent(response.symbol(), response.version(), response.status(),
                eventType, eventTime, response);
        outboxService.enqueue("INSTRUMENT", response.version(), properties.getKafka().getEventsTopic(),
                response.symbol(), eventType.name(), event, eventTime);
    }

    @Transactional
    public void publishProductLifecycleEvent(InstrumentResponse response) {
        Instant eventTime = Instant.now();
        if (response.instrumentType() == InstrumentType.DELIVERY) {
            outboxService.enqueue("INSTRUMENT", response.version(), deliverySettlementsTopic(response),
                    response.symbol(), "DELIVERY_SETTLEMENT", new DeliverySettlementEvent(
                    response.symbol(),
                    response.version(),
                    response.contractType(),
                    response.expiryTime(),
                    response.deliveryTime(),
                    response.settlementMethod(),
                    response.status(),
                    eventTime,
                    response), eventTime);
            return;
        }
        if (response.instrumentType() == InstrumentType.OPTION) {
            outboxService.enqueue("INSTRUMENT", response.version(), optionExercisesTopic(response),
                    response.symbol(), "OPTION_EXERCISE", new OptionExerciseEvent(
                    response.symbol(),
                    response.version(),
                    response.underlyingSymbol(),
                    response.strikePriceUnits(),
                    response.optionType(),
                    response.optionExerciseStyle(),
                    response.expiryTime(),
                    response.deliveryTime(),
                    response.settlementMethod(),
                    response.status(),
                    eventTime,
                    response), eventTime);
        }
    }

    /**
     * 关闭到期品种时，把状态变更和产品结算事件写入同一个数据库事务。
     */
    @Transactional
    public InstrumentResponse closeForSettlement(String symbol) {
        InstrumentResponse closed = updateStatus(symbol, InstrumentStatus.CLOSED);
        publishProductLifecycleEvent(closed);
        return closed;
    }

    private String deliverySettlementsTopic(InstrumentResponse response) {
        String override = properties.getKafka().getDeliverySettlementsTopic();
        if (override != null && !override.isBlank()) {
            return override;
        }
        return ProductTopicNames.of(response.contractType().productLine()).deliverySettlementsTopic();
    }

    private String optionExercisesTopic(InstrumentResponse response) {
        String override = properties.getKafka().getOptionExercisesTopic();
        if (override != null && !override.isBlank()) {
            return override;
        }
        return ProductTopicNames.of(response.contractType().productLine()).optionExercisesTopic();
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return symbol.trim().toUpperCase();
    }
}
