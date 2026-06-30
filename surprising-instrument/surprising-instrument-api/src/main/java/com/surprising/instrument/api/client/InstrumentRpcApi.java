package com.surprising.instrument.api.client;

import com.surprising.instrument.api.InstrumentApiPaths;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import jakarta.validation.constraints.NotBlank;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "surprising-instrument-provider", contextId = "instrumentRpcApi")
@RequestMapping(InstrumentApiPaths.BASE_PATH)
public interface InstrumentRpcApi {

    @GetMapping("/latest")
    InstrumentResponse latest(@RequestParam("symbol") @NotBlank String symbol);

    @GetMapping("/version")
    InstrumentResponse version(@RequestParam("symbol") @NotBlank String symbol,
                               @RequestParam("version") long version);

    @GetMapping("/list")
    InstrumentQueryResponse list(@RequestParam(value = "type", required = false) InstrumentType type,
                                 @RequestParam(value = "status", required = false) InstrumentStatus status);
}
