package com.surprising.instrument.api.client;

import com.surprising.instrument.api.InstrumentApiPaths;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
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

    @GetMapping("/{symbol}")
    InstrumentResponse latest(@PathVariable("symbol") @NotBlank String symbol);

    @GetMapping("/list")
    InstrumentQueryResponse list(@RequestParam(value = "type", required = false) InstrumentType type,
                                 @RequestParam(value = "status", required = false) InstrumentStatus status,
                                 @RequestParam(value = "limit", defaultValue = "100") int limit,
                                 @RequestParam(value = "cursor", required = false) String cursor,
                                 @RequestParam(value = "sort", required = false) String sort);

    @GetMapping("/{symbol}/versions")
    InstrumentQueryResponse versions(@PathVariable("symbol") @NotBlank String symbol,
                                     @RequestParam(value = "limit", defaultValue = "100") int limit,
                                     @RequestParam(value = "cursor", required = false) String cursor,
                                     @RequestParam(value = "sort", required = false) String sort);

    @PostMapping("/upsert")
    InstrumentResponse upsert(@RequestBody @Valid InstrumentUpsertRequest request);

    @PostMapping("/{symbol}/status")
    InstrumentResponse updateStatus(@PathVariable("symbol") @NotBlank String symbol,
                                    @RequestParam("status") @NotNull InstrumentStatus status);
}
