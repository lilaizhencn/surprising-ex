package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import org.springframework.web.bind.annotation.*;

@RestController
public class InstrumentCoreSyncController {
    private final InstrumentCoreSyncService sync;
    public InstrumentCoreSyncController(InstrumentCoreSyncService sync) { this.sync=sync; }
    @GetMapping("/api/v1/admin/trading/orders/instrument-sync/{symbol}")
    public InstrumentCoreSyncService.SyncState state(@PathVariable String symbol, @RequestParam ProductLine productLine) {
        return sync.state(symbol,productLine);
    }
}
