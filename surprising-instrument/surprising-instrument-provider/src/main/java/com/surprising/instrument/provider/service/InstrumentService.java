package com.surprising.instrument.provider.service;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentEventType;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.provider.config.InstrumentProperties;
import com.surprising.instrument.provider.repository.InstrumentRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstrumentService {

    private final InstrumentRepository instrumentRepository;
    private final InstrumentValidator instrumentValidator;
    private final InstrumentProperties properties;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public InstrumentService(InstrumentRepository instrumentRepository,
                             InstrumentValidator instrumentValidator,
                             InstrumentProperties properties,
                             KafkaTemplate<String, Object> kafkaTemplate) {
        this.instrumentRepository = instrumentRepository;
        this.instrumentValidator = instrumentValidator;
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
    }

    public InstrumentResponse latest(String symbol) {
        return instrumentRepository.latest(normalizeSymbol(symbol))
                .orElseThrow(() -> new IllegalStateException("instrument not found: " + symbol));
    }

    public InstrumentResponse version(String symbol, long version) {
        return instrumentRepository.version(normalizeSymbol(symbol), version)
                .orElseThrow(() -> new IllegalStateException("instrument version not found: " + symbol + ":" + version));
    }

    public InstrumentQueryResponse list(InstrumentType type, InstrumentStatus status) {
        var rows = instrumentRepository.list(type, status);
        return new InstrumentQueryResponse(rows.size(), rows);
    }

    public InstrumentQueryResponse list(InstrumentType type, InstrumentStatus status, int limit, String cursor, String sort) {
        var page = instrumentRepository.listPage(type, status, limit, cursor, sort);
        return new InstrumentQueryResponse(page.instruments().size(), page.instruments(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public InstrumentQueryResponse versions(String symbol, int limit, String cursor, String sort) {
        var page = instrumentRepository.versionsPage(normalizeSymbol(symbol), limit, cursor, sort);
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
        long version = instrumentRepository.nextVersion(symbol);
        instrumentRepository.insert(symbol, version, request, now);
        instrumentRepository.setCurrentVersion(symbol, version, now);
        InstrumentResponse response = instrumentRepository.version(symbol, version)
                .orElseThrow(() -> new IllegalStateException("instrument insert failed: " + symbol));
        publish(response, eventType);
        return response;
    }

    @Transactional
    public InstrumentResponse updateStatus(String symbol, InstrumentStatus status) {
        InstrumentResponse current = latest(symbol);
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
                current.impactNotionalUnits(), current.minValidIndexSources(), status, Instant.now(),
                current.riskLimitBrackets(), current.indexSources());
        return upsert(request, InstrumentEventType.STATUS_CHANGED);
    }

    private void publish(InstrumentResponse response, InstrumentEventType eventType) {
        InstrumentEvent event = new InstrumentEvent(response.symbol(), response.version(), response.status(),
                eventType, Instant.now(), response);
        kafkaTemplate.send(properties.getKafka().getEventsTopic(), response.symbol(), event);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return symbol.trim().toUpperCase();
    }
}
