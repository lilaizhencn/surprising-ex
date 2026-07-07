package com.surprising.instrument.provider.controller;

import com.surprising.instrument.api.InstrumentApiPaths;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.product.api.ProductLine;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InstrumentController {

    private final InstrumentService instrumentService;

    public InstrumentController(InstrumentService instrumentService) {
        this.instrumentService = instrumentService;
    }

    @GetMapping(InstrumentApiPaths.BASE_PATH + "/latest")
    public InstrumentResponse latest(@RequestParam("symbol") String symbol,
                                     @RequestHeader(value = "X-Product-Line", required = false)
                                     String productLineHeader,
                                     @RequestParam(value = "productLine", required = false)
                                     String productLineValue) {
        try {
            return instrumentService.latest(symbol, productLine(productLineValue, productLineHeader));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(InstrumentApiPaths.BASE_PATH + "/version")
    public InstrumentResponse version(@RequestParam("symbol") String symbol, @RequestParam("version") long version) {
        try {
            return instrumentService.version(symbol, version);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(InstrumentApiPaths.BASE_PATH + "/list")
    public InstrumentQueryResponse list(@RequestParam(value = "type", required = false) InstrumentType type,
                                        @RequestParam(value = "status", required = false) InstrumentStatus status) {
        return instrumentService.list(type, status);
    }

    @GetMapping(InstrumentApiPaths.ADMIN_BASE_PATH + "/{symbol}")
    public InstrumentResponse adminLatest(@PathVariable("symbol") String symbol) {
        try {
            return instrumentService.latest(symbol);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(InstrumentApiPaths.ADMIN_BASE_PATH + "/list")
    public InstrumentQueryResponse adminList(@RequestParam(value = "type", required = false) InstrumentType type,
                                             @RequestParam(value = "status", required = false) InstrumentStatus status,
                                             @RequestParam(value = "limit", defaultValue = "100") int limit,
                                             @RequestParam(value = "cursor", required = false) String cursor,
                                             @RequestParam(value = "sort", required = false) String sort) {
        try {
            return instrumentService.list(type, status, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(InstrumentApiPaths.ADMIN_BASE_PATH + "/{symbol}/versions")
    public InstrumentQueryResponse versions(@PathVariable("symbol") String symbol,
                                            @RequestParam(value = "limit", defaultValue = "100") int limit,
                                            @RequestParam(value = "cursor", required = false) String cursor,
                                            @RequestParam(value = "sort", required = false) String sort) {
        try {
            return instrumentService.versions(symbol, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(InstrumentApiPaths.ADMIN_BASE_PATH + "/upsert")
    public InstrumentResponse upsert(@RequestBody InstrumentUpsertRequest request) {
        try {
            return instrumentService.upsert(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(InstrumentApiPaths.ADMIN_BASE_PATH + "/{symbol}/status")
    public InstrumentResponse updateStatus(@PathVariable("symbol") String symbol,
                                           @RequestParam("status") InstrumentStatus status) {
        try {
            return instrumentService.updateStatus(symbol, status);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    private ProductLine productLine(String queryValue, String headerValue) {
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
