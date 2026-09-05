package com.surprising.price.probe;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/price/market-probe")
public class MarketProbeController {

    private final MarketProbeService marketProbeService;

    public MarketProbeController(MarketProbeService marketProbeService) {
        this.marketProbeService = marketProbeService;
    }

    @GetMapping
    public MarketProbeService.MarketProbeSnapshot snapshot(@RequestParam("symbol") String symbol,
                                                            @RequestParam(value = "sourceMode",
                                                                    defaultValue = "PUBLIC_WEBSOCKET_ONLY")
                                                            MarketProbeService.SourceMode sourceMode) {
        return marketProbeService.snapshot(symbol, sourceMode);
    }
}
