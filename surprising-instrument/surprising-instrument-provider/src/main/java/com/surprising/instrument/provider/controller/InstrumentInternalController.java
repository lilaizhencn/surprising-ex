package com.surprising.instrument.provider.controller;

import com.surprising.instrument.api.InstrumentApiPaths;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentSnapshotResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.product.api.ProductLine;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Instrument 服务间只读入口，业务模块通过该入口获取完整聚合快照。 */
@RestController
@RequestMapping(InstrumentApiPaths.INTERNAL_BASE_PATH)
public class InstrumentInternalController {

    private final InstrumentService instrumentService;

    public InstrumentInternalController(InstrumentService instrumentService) {
        this.instrumentService = instrumentService;
    }

    @GetMapping("/latest")
    public InstrumentResponse latest(@RequestParam("symbol") String symbol,
                                     @RequestParam(value = "productLine", required = false)
                                     String productLineValue,
                                     @RequestHeader(value = "X-Product-Line", required = false)
                                     String productLineHeader) {
        try {
            return instrumentService.latest(symbol, productLine(productLineValue, productLineHeader));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping("/version")
    public InstrumentResponse version(@RequestParam("symbol") String symbol,
                                      @RequestParam("version") long version) {
        try {
            return instrumentService.version(symbol, version);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping("/list")
    public InstrumentQueryResponse list(@RequestParam(value = "type", required = false) InstrumentType type,
                                        @RequestParam(value = "status", required = false) InstrumentStatus status,
                                        @RequestParam(value = "productLine", required = false)
                                        String productLineValue,
                                        @RequestHeader(value = "X-Product-Line", required = false)
                                        String productLineHeader) {
        try {
            return instrumentService.list(productLine(productLineValue, productLineHeader), type, status);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/snapshot")
    public InstrumentSnapshotResponse snapshot(@RequestParam(value = "productLine", required = false)
                                               String productLineValue,
                                               @RequestHeader(value = "X-Product-Line", required = false)
                                               String productLineHeader) {
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            if (productLine == null) {
                throw new IllegalArgumentException("productLine is required");
            }
            return instrumentService.snapshot(productLine);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
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
