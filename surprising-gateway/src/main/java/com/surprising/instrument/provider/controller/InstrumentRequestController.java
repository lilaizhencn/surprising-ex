package com.surprising.instrument.provider.controller;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.instrument.provider.model.InstrumentPageQuery;
import com.surprising.instrument.provider.exception.InstrumentOperationException;
import com.surprising.product.api.ProductLine;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Component;

/**
 * 网关本地协议的参数绑定、响应转换和HTTP异常映射；路由仍由InstrumentLocalRoutes分发。
 */
@Component
public class InstrumentRequestController {

    private final InstrumentService instrumentService;
    private final ObjectMapper objectMapper;

    public InstrumentRequestController(InstrumentService instrumentService,
                                    ObjectMapper objectMapper) {
        this.instrumentService = instrumentService;
        this.objectMapper = objectMapper;
    }

    public InstrumentResponse latest(int instrumentId, String productLineHeader, String productLineValue) {
        try {
            return instrumentService.publicLatest(instrumentId, requiredProductLine(productLineValue, productLineHeader));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (InstrumentOperationException ex) {
            throw httpError(ex);
        }
    }

    public InstrumentResponse defaultInstrument(String header, String query) {
        try {
            var line = productLine(query, header);
            if (line == null) throw new IllegalArgumentException("productLine is required");
            return instrumentService.defaultInstrument(line);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        } catch (InstrumentOperationException error) {
            throw httpError(error);
        }
    }

    public Map<String, String> assetScales() {
        return instrumentService.assetScales().entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Long.toString(entry.getValue())));
    }

    public Object list(InstrumentType type, InstrumentStatus status, String productLineHeader,
                       String productLineValue, boolean includeMarketSummary, boolean includeTrend) {
        try {
            var catalog = instrumentService.publicCatalog(productLine(productLineValue, productLineHeader), type, status, includeMarketSummary);
            var instruments = catalog.instruments();
            if (!includeMarketSummary) return instruments;
            var summaries = catalog.summaries();
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
        } catch (InstrumentOperationException ex) {
            throw httpError(ex);
        }
    }

    public InstrumentQueryResponse adminList(InstrumentType type, InstrumentStatus status, int limit, String cursor, String sort, String productLineHeader, String productLineValue) {
        try {
            return instrumentService.list(productLine(productLineValue, productLineHeader), type, status, pageQuery(limit, cursor, sort));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public java.util.List<com.surprising.instrument.provider.repository.InstrumentChangeLogRepository.Entry> changes(int instrumentId, ProductLine productLine, long beforeId, int limit) {
        return instrumentService.changes(instrumentId, productLine, beforeId, limit);
    }

    public InstrumentResponse edit(InstrumentUpsertRequest request, String operator, String reason, long expectedChangeId) {
        try {
            requireAudit(operator, reason);
            com.surprising.instrument.provider.utils.InstrumentConfigurationFormats.validate(request);
            return instrumentService.edit(request, operator, reason, expectedChangeId);
        } catch (InstrumentOperationException conflict) {
            throw httpError(conflict);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    public InstrumentResponse editStatus(int instrumentId, InstrumentStatus status, String header, String query,
                                         String operator, String reason, long expectedChangeId) {
        try {
            requireAudit(operator, reason);
            return instrumentService.editStatus(instrumentId, requiredProductLine(query, header), status,
                    operator, reason, expectedChangeId);
        } catch (InstrumentOperationException conflict) {
            throw httpError(conflict);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    public InstrumentResponse upsert(InstrumentUpsertRequest request, String operator, String reason) {
        try {
            requireAudit(operator, reason);
            com.surprising.instrument.provider.utils.InstrumentConfigurationFormats.validate(request);
            return instrumentService.upsert(request, operator, reason);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public InstrumentResponse updateStatus(int instrumentId, InstrumentStatus status, String productLineHeader, String productLineValue, String operator, String reason) {
        try {
            requireAudit(operator, reason);
            return instrumentService.updateStatus(instrumentId, requiredProductLine(productLineValue, productLineHeader), status, operator, reason);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (InstrumentOperationException ex) {
            throw httpError(ex);
        }
    }

    public InstrumentResponse closeForSettlement(int instrumentId, String productLineHeader, String productLineValue, long settlementPriceTicks, long underlyingSettlementPriceUnits, String operator, String reason) {
        try {
            requireAudit(operator, reason);
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            if (productLine == null) {
                throw new IllegalArgumentException("settlement productLine is required");
            }
            return instrumentService.closeForSettlement(instrumentId, productLine, settlementPriceTicks, underlyingSettlementPriceUnits, operator, reason);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (InstrumentOperationException ex) {
            throw httpError(ex);
        }
    }

    private void requireAudit(String operator, String reason) {
        if (operator == null || operator.isBlank() || reason == null || reason.isBlank() || reason.length() > 1000)
            throw new IllegalArgumentException("administrator and change reason (1-1000 characters) are required");
    }

    private static ResponseStatusException httpError(InstrumentOperationException error) {
        return new ResponseStatusException(switch (error.reason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
        }, error.getMessage(), error);
    }

    private static InstrumentPageQuery pageQuery(int limit, String cursor, String sort) {
        String token = sort == null || sort.isBlank() ? "symbol.asc" : sort.trim();
        var order = java.util.Arrays.stream(InstrumentPageQuery.Sort.values()).filter(value -> value.token().equals(token))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unsupported sort: " + sort));
        InstrumentPageQuery.Cursor decoded = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String raw = new String(java.util.Base64.getUrlDecoder().decode(cursor.trim()), java.nio.charset.StandardCharsets.UTF_8);
                int split = raw.lastIndexOf('|');
                if (split <= 0 || split == raw.length() - 1) throw new IllegalArgumentException("invalid cursor");
                var timestamp = order.symbolSort() ? null : java.time.Instant.parse(raw.substring(0, split));
                decoded = new InstrumentPageQuery.Cursor(raw.substring(split + 1), timestamp);
            } catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid cursor", invalid); }
        }
        return new InstrumentPageQuery(Math.max(1, Math.min(limit, 1000)), order, decoded);
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
