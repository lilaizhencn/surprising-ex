package com.surprising.instrument.provider.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.provider.repository.MarketSummaryRepository;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.product.api.ProductLine;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;

/**
 * 共享原始 HTTP 与网关入口的请求校验、业务编排和结果转换；不持有 HTTP 路由。
 */
@Service()
public class InstrumentRequestService {

    private final InstrumentService instrumentService;
    private final MarketSummaryRepository marketSummaries;
    private final ObjectMapper objectMapper;

    public InstrumentRequestService(InstrumentService instrumentService,
                                    MarketSummaryRepository marketSummaries,
                                    ObjectMapper objectMapper) {
        this.instrumentService = instrumentService;
        this.marketSummaries = marketSummaries;
        this.objectMapper = objectMapper;
    }

    public InstrumentResponse latest(int instrumentId, String productLineHeader, String productLineValue) {
        try {
            var result = instrumentService.latest(instrumentId, requiredProductLine(productLineValue, productLineHeader));
            if (!result.status().visible()) throw new IllegalStateException("instrument is not listed");
            return result;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public InstrumentResponse defaultInstrument(String header, String query) {
        try {
            var line = productLine(query, header);
            if (line == null) throw new IllegalArgumentException("productLine is required");
            return instrumentService.defaultInstrument(line);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
        }
    }

    public Map<String, String> assetScales() {
        return instrumentService.assetScales().entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Long.toString(entry.getValue())));
    }

    public Object list(InstrumentType type, InstrumentStatus status, String productLineHeader,
                       String productLineValue, boolean includeMarketSummary, boolean includeTrend) {
        try {
            var all = instrumentService.list(productLine(productLineValue, productLineHeader), type, status);
            var visible = all.instruments().stream().filter(value -> value.status().visible()).toList();
            var instruments = new InstrumentQueryResponse(visible.size(), visible);
            if (!includeMarketSummary) return instruments;
            var summaries = marketSummaries.summaries(
                    instruments.instruments().stream().map(InstrumentResponse::instrumentId).distinct().toList(),
                    java.time.Instant.now());
            List<Map<String, Object>> items = instruments.instruments().stream().map(instrument -> {
                Map<String, Object> item = objectMapper.convertValue(instrument, new TypeReference<LinkedHashMap<String, Object>>() {});
                var summary = summaries.get(instrument.instrumentId());
                if (summary != null) {
                    item.put("lastPrice", summary.lastPrice());
                    item.put("change24h", summary.change24h());
                    item.put("high24h", summary.high24h());
                    item.put("low24h", summary.low24h());
                    item.put("volume24h", summary.volume24h());
                    item.put("quoteVolume24h", summary.quoteVolume24h());
                    if (includeTrend) item.put("trend", summary.trend());
                } else if (includeTrend) item.put("trend", List.of());
                return item;
            }).toList();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("count", instruments.count());
            response.put("instruments", items);
            response.put("nextCursor", instruments.nextCursor());
            response.put("hasMore", instruments.hasMore());
            response.put("sort", instruments.sort());
            response.put("limit", instruments.limit());
            return response;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public InstrumentResponse adminLatest(int instrumentId, String productLineHeader, String productLineValue) {
        try {
            return instrumentService.latest(instrumentId, requiredProductLine(productLineValue, productLineHeader));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public InstrumentQueryResponse adminList(InstrumentType type, InstrumentStatus status, int limit, String cursor, String sort, String productLineHeader, String productLineValue) {
        try {
            return instrumentService.list(productLine(productLineValue, productLineHeader), type, status, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public java.util.List<com.surprising.instrument.provider.repository.InstrumentChangeLogRepository.Entry> changes(int instrumentId, ProductLine productLine, long beforeId, int limit) {
        return instrumentService.changes(instrumentId, productLine, beforeId, limit);
    }

    public InstrumentResponse edit(InstrumentUpsertRequest request, String operator, String reason, long expectedChangeId) {
        try {
            return instrumentService.edit(request, operator, reason, expectedChangeId);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    public InstrumentResponse editStatus(int instrumentId, InstrumentStatus status, String header, String query,
                                         String operator, String reason, long expectedChangeId) {
        try {
            return instrumentService.editStatus(instrumentId, requiredProductLine(query, header), status,
                    operator, reason, expectedChangeId);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    public InstrumentResponse upsert(InstrumentUpsertRequest request, String operator, String reason) {
        try {
            return instrumentService.upsert(request, operator, reason);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public InstrumentResponse updateStatus(int instrumentId, InstrumentStatus status, String productLineHeader, String productLineValue, String operator, String reason) {
        try {
            return instrumentService.updateStatus(instrumentId, requiredProductLine(productLineValue, productLineHeader), status, operator, reason);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public InstrumentResponse closeForSettlement(int instrumentId, String productLineHeader, String productLineValue, long settlementPriceTicks, long underlyingSettlementPriceUnits, String operator, String reason) {
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            if (productLine == null) {
                throw new IllegalArgumentException("settlement productLine is required");
            }
            return instrumentService.closeForSettlement(instrumentId, productLine, settlementPriceTicks, underlyingSettlementPriceUnits, operator, reason);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private ProductLine requiredProductLine(String queryValue, String headerValue) {
        ProductLine value = productLine(queryValue, headerValue);
        if (value == null) throw new IllegalArgumentException("productLine is required for instrument identity");
        return value;
    }

    private ProductLine productLine(String queryValue, String headerValue) {
        if (queryValue != null && !queryValue.isBlank() && headerValue != null && !headerValue.isBlank()
                && productLine(queryValue, null) != productLine(null, headerValue)) {
            throw new IllegalArgumentException("productLine query and header disagree");
        }
        String value = queryValue != null && !queryValue.isBlank() ? queryValue : headerValue;
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        String enumName = normalized.toUpperCase(Locale.ROOT).replace('-', '_');
        for (ProductLine productLine : ProductLine.values()) {
            if (productLine.name().equals(enumName) || productLine.topicSegment().equalsIgnoreCase(normalized) || productLine.accountTypeCode().equalsIgnoreCase(normalized) || productLine.contractTypeCode().equalsIgnoreCase(normalized)) {
                return productLine;
            }
        }
        throw new IllegalArgumentException("unsupported productLine: " + value);
    }
}
