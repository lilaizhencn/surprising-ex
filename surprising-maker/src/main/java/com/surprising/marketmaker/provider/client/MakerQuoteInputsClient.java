package com.surprising.marketmaker.provider.client;

import com.surprising.trading.api.model.MakerQuoteInputs;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "surprising-gateway", url = "${surprising.clients.gateway.url:http://127.0.0.1:9094}", contextId = "makerQuoteInputsClient")
public interface MakerQuoteInputsClient {
    @PostMapping("/internal/v1/trading/maker/quote-inputs")
    MakerQuoteInputs.Response query(@RequestBody MakerQuoteInputs.Request request);
}
