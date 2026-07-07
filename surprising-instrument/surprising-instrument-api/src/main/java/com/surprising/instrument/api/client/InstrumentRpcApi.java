package com.surprising.instrument.api.client;

import com.surprising.instrument.api.InstrumentApiPaths;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import jakarta.validation.constraints.NotBlank;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "surprising-instrument-provider",
        contextId = "instrumentRpcApi",
        path = InstrumentApiPaths.BASE_PATH,
        url = "${surprising.clients.instrument.base-url:http://localhost:9080}")
public interface InstrumentRpcApi {

    default InstrumentResponse latest(String symbol) {
        return latest(symbol, null);
    }

    @GetMapping("/latest")
    InstrumentResponse latest(@RequestParam("symbol") @NotBlank String symbol,
                              @RequestParam(value = "productLine", required = false) ProductLine productLine);

    @GetMapping("/version")
    InstrumentResponse version(@RequestParam("symbol") @NotBlank String symbol,
                               @RequestParam("version") long version);

    default InstrumentQueryResponse list(InstrumentType type, InstrumentStatus status) {
        return list(null, type, status);
    }

    @GetMapping("/list")
    InstrumentQueryResponse list(@RequestParam(value = "productLine", required = false) ProductLine productLine,
                                 @RequestParam(value = "type", required = false) InstrumentType type,
                                 @RequestParam(value = "status", required = false) InstrumentStatus status);
}
