package com.surprising.instrument.provider.service;

import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.product.api.ProductLine;
import java.util.Locale;
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

    public InstrumentRequestService(InstrumentService instrumentService) {
        this.instrumentService = instrumentService;
    }

    public InstrumentResponse latest(int instrumentId, String productLineHeader, String productLineValue) {
        try {
            return instrumentService.latest(instrumentId, requiredProductLine(productLineValue, productLineHeader));
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

    public InstrumentQueryResponse list(InstrumentType type, InstrumentStatus status, String productLineHeader, String productLineValue) {
        try {
            return instrumentService.list(productLine(productLineValue, productLineHeader), type, status);
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
