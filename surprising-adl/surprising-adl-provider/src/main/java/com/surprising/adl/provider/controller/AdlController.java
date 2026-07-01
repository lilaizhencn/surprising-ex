package com.surprising.adl.provider.controller;

import com.surprising.adl.api.AdlApiPaths;
import com.surprising.adl.api.model.AdlEventQueryResponse;
import com.surprising.adl.api.model.AdlQueueQueryResponse;
import com.surprising.adl.provider.service.AdlService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(AdlApiPaths.API_V1)
public class AdlController {

    private final AdlService adlService;

    public AdlController(AdlService adlService) {
        this.adlService = adlService;
    }

    @GetMapping("/queue")
    public AdlQueueQueryResponse queue(@RequestParam String asset,
                                       @RequestParam(defaultValue = "100") int limit) {
        return adlService.queue(asset, limit);
    }

    @GetMapping("/events")
    public AdlEventQueryResponse events(@RequestParam(required = false) Long userId,
                                        @RequestParam(required = false) String asset,
                                        @RequestParam(required = false) String symbol,
                                        @RequestParam(defaultValue = "100") int limit) {
        return adlService.events(userId, asset, symbol, limit);
    }
}
