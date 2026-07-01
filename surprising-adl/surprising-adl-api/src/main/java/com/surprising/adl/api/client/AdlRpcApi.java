package com.surprising.adl.api.client;

import com.surprising.adl.api.AdlApiPaths;
import com.surprising.adl.api.model.AdlEventQueryResponse;
import com.surprising.adl.api.model.AdlQueueQueryResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "surprising-adl-provider",
        path = AdlApiPaths.API_V1,
        url = "${surprising.clients.adl.base-url:http://localhost:9091}")
public interface AdlRpcApi {

    @GetMapping("/queue")
    AdlQueueQueryResponse queue(@RequestParam("asset") String asset,
                                @RequestParam(value = "limit", defaultValue = "100") int limit);

    @GetMapping("/events")
    AdlEventQueryResponse events(@RequestParam(value = "userId", required = false) Long userId,
                                 @RequestParam(value = "asset", required = false) String asset,
                                 @RequestParam(value = "symbol", required = false) String symbol,
                                 @RequestParam(value = "limit", defaultValue = "100") int limit);
}
