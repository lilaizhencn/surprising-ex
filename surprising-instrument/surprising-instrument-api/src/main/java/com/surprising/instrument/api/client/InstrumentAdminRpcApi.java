package com.surprising.instrument.api.client;

import com.surprising.instrument.api.InstrumentApiPaths;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.product.api.ProductLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "surprising-instrument-provider",
        contextId = "instrumentAdminRpcApi",
        path = InstrumentApiPaths.ADMIN_BASE_PATH,
        url = "${surprising.clients.instrument.base-url:http://localhost:9080}")
public interface InstrumentAdminRpcApi {

    default InstrumentResponse latest(String symbol) {
        return latest(symbol, null);
    }

    @GetMapping("/{symbol}")
    InstrumentResponse latest(@PathVariable("symbol") @NotBlank String symbol,
                              @RequestParam(value = "productLine", required = false) ProductLine productLine);

    default InstrumentQueryResponse list(InstrumentType type,
                                         InstrumentStatus status,
                                         int limit,
                                         String cursor,
                                         String sort) {
        return list(null, type, status, limit, cursor, sort);
    }

    @GetMapping("/list")
    InstrumentQueryResponse list(@RequestParam(value = "productLine", required = false) ProductLine productLine,
                                 @RequestParam(value = "type", required = false) InstrumentType type,
                                 @RequestParam(value = "status", required = false) InstrumentStatus status,
                                 @RequestParam(value = "limit", defaultValue = "100") int limit,
                                 @RequestParam(value = "cursor", required = false) String cursor,
                                 @RequestParam(value = "sort", required = false) String sort);

    default InstrumentQueryResponse versions(String symbol, int limit, String cursor, String sort) {
        return versions(symbol, null, limit, cursor, sort);
    }

    @GetMapping("/{symbol}/versions")
    InstrumentQueryResponse versions(@PathVariable("symbol") @NotBlank String symbol,
                                     @RequestParam(value = "productLine", required = false) ProductLine productLine,
                                     @RequestParam(value = "limit", defaultValue = "100") int limit,
                                     @RequestParam(value = "cursor", required = false) String cursor,
                                     @RequestParam(value = "sort", required = false) String sort);

    @PostMapping("/upsert")
    InstrumentResponse upsert(@RequestBody @Valid InstrumentUpsertRequest request);

    default InstrumentResponse updateStatus(String symbol, InstrumentStatus status) {
        return updateStatus(symbol, null, status);
    }

    @PostMapping("/{symbol}/status")
    InstrumentResponse updateStatus(@PathVariable("symbol") @NotBlank String symbol,
                                    @RequestParam(value = "productLine", required = false) ProductLine productLine,
                                    @RequestParam("status") @NotNull InstrumentStatus status);
}
