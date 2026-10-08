package com.surprising.marketmaker.provider.client;

import com.surprising.trading.api.model.MakerQuoteInputs;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "surprising-trading-provider", contextId = "makerQuoteInputsClient",
        url = "${surprising.clients.trading.base-url:http://localhost:9094}")
public interface MakerQuoteInputsClient {
    @PostMapping("/internal/v1/trading/maker/quote-inputs")
    MakerQuoteInputs.Response query(@RequestBody MakerQuoteInputs.Request request);
}
