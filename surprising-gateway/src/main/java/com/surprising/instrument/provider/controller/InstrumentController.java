package com.surprising.instrument.provider.controller;

import com.surprising.gateway.provider.product.GatewayUserContext;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.provider.exception.InstrumentOperationException;
import com.surprising.instrument.provider.model.InstrumentPageQuery;
import com.surprising.instrument.provider.repository.InstrumentChangeLogRepository;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.instrument.provider.utils.InstrumentConfigurationFormats;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/instruments")
public class InstrumentController {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final InstrumentService instrumentService;
    private final ObjectMapper objectMapper;

    public InstrumentController(InstrumentService instrumentService, ObjectMapper objectMapper) {
        this.instrumentService = instrumentService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/default")
    public InstrumentResponse defaultInstrument(
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        try {
            var line = productLine(queryProductLine, headerProductLine);
            if (line == null) throw new IllegalArgumentException("productLine is required");
            return instrumentService.defaultInstrument(line);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        } catch (InstrumentOperationException error) {
            throw httpError(error);
        }
    }

    @GetMapping("/asset-scales")
    public Map<String, String> assetScales() {
        return instrumentService.assetScales().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Long.toString(entry.getValue())));
    }

    @GetMapping("/latest")
    public InstrumentResponse latest(
            @RequestParam("instrumentId") int instrumentId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        try {
            return instrumentService.publicLatest(instrumentId, requiredProductLine(queryProductLine, headerProductLine));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (InstrumentOperationException ex) {
            throw httpError(ex);
        }
    }

    @GetMapping("/list")
    public Object list(
            @RequestParam(value = "type", required = false) InstrumentType type,
            @RequestParam(value = "status", required = false) InstrumentStatus status,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestParam(value = "includeMarketSummary", defaultValue = "false") boolean includeMarketSummary,
            @RequestParam(value = "includeTrend", defaultValue = "false") boolean includeTrend) {
        try {
            var catalog = instrumentService.publicCatalog(productLine(queryProductLine, headerProductLine), type, status, includeMarketSummary);
            var instruments = catalog.instruments();
            if (!includeMarketSummary) return instruments;
            var summaries = catalog.summaries();
            List<Map<String, Object>> items = instruments.instruments().stream().map(instrument -> {
                Map<String, Object> item = objectMapper.convertValue(instrument, MAP_TYPE_REF);
                var summary = summaries.get(instrument.instrumentId());
                if (summary != null) {
                    item.put("lastPrice", summary.lastPrice());
                    item.put("change24h", summary.change24h());
                    item.put("high24h", summary.high24h());
                    item.put("low24h", summary.low24h());
                    item.put("volume24h", summary.volume24h());
                    item.put("quoteVolume24h", summary.quoteVolume24h());
                    if (includeTrend) item.put("trend", summary.trend());
                } else if (includeTrend) {
                    item.put("trend", List.of());
                }
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

    @GetMapping("/day-summary")
    public Object daySummary(
            @RequestParam("instrumentId") int instrumentId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        try {
            var line = requiredProductLine(queryProductLine, headerProductLine);
            var summary = instrumentService.daySummary(line, instrumentId);
            return summary == null ? Map.of() : summary;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/admin/upsert")
    public InstrumentResponse upsert(
            @RequestBody InstrumentUpsertRequest request,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("reason") String reason,
            @RequestParam("expectedChangeId") long expectedChangeId) {
        String operator = GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            requireAudit(operator, reason);
            InstrumentConfigurationFormats.validate(request);
            return instrumentService.edit(request, operator, reason, expectedChangeId);
        } catch (InstrumentOperationException conflict) {
            throw httpError(conflict);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    @GetMapping("/admin/list")
    public InstrumentQueryResponse adminList(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "type", required = false) InstrumentType type,
            @RequestParam(value = "status", required = false) InstrumentStatus status,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            return instrumentService.list(productLine(queryProductLine, headerProductLine), type, status, pageQuery(limit, cursor, sort));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/admin/{instrumentId}/settlement")
    public InstrumentResponse closeForSettlement(
            @PathVariable("instrumentId") int instrumentId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestParam("settlementPriceTicks") long settlementPriceTicks,
            @RequestParam(value = "underlyingSettlementPriceUnits", defaultValue = "0") long underlyingSettlementPriceUnits,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "reason", defaultValue = "Admin settlement confirmation") String reason) {
        String operator = GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            requireAudit(operator, reason);
            ProductLine productLine = productLine(queryProductLine, headerProductLine);
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

    @GetMapping("/admin/{instrumentId}/changes")
    public List<InstrumentChangeLogRepository.Entry> changes(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("instrumentId") int instrumentId,
            @RequestParam("productLine") ProductLine productLine,
            @RequestParam(value = "beforeId", defaultValue = "0") long beforeId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return instrumentService.changes(instrumentId, productLine, beforeId, limit);
    }

    @PostMapping("/admin/{instrumentId}/status")
    public InstrumentResponse updateStatus(
            @PathVariable("instrumentId") int instrumentId,
            @RequestParam("status") InstrumentStatus status,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("reason") String reason,
            @RequestParam("expectedChangeId") long expectedChangeId) {
        String operator = GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            requireAudit(operator, reason);
            return instrumentService.editStatus(instrumentId, requiredProductLine(queryProductLine, headerProductLine), status,
                    operator, reason, expectedChangeId);
        } catch (InstrumentOperationException conflict) {
            throw httpError(conflict);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    @GetMapping("/admin/{instrumentId}")
    public InstrumentResponse adminLatest(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("instrumentId") int instrumentId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            return instrumentService.latest(instrumentId, requiredProductLine(queryProductLine, headerProductLine));
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
        var order = Arrays.stream(InstrumentPageQuery.Sort.values()).filter(value -> value.token().equals(token))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unsupported sort: " + sort));
        InstrumentPageQuery.Cursor decoded = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
                int split = raw.lastIndexOf('|');
                if (split <= 0 || split == raw.length() - 1) throw new IllegalArgumentException("invalid cursor");
                var timestamp = order.symbolSort() ? null : Instant.parse(raw.substring(0, split));
                decoded = new InstrumentPageQuery.Cursor(raw.substring(split + 1), timestamp);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("invalid cursor", invalid);
            }
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
            if (productLine.name().equals(enumName)
                    || productLine.topicSegment().equalsIgnoreCase(normalized)
                    || productLine.accountTypeCode().equalsIgnoreCase(normalized)
                    || productLine.contractTypeCode().equalsIgnoreCase(normalized)) {
                return productLine;
            }
        }
        throw new IllegalArgumentException("unsupported productLine: " + value);
    }
}
