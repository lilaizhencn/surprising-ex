package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.product.GatewayUserIdentity;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.service.InstrumentCoreSyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative instrument core-sync state endpoints.
 */
@RestController
public class AdminTradingSyncController {

    private final InstrumentCoreSyncService instrumentCoreSyncService;

    public AdminTradingSyncController(InstrumentCoreSyncService instrumentCoreSyncService) {
        this.instrumentCoreSyncService = instrumentCoreSyncService;
    }

    @GetMapping("/api/v1/admin/trading/orders/instrument-sync/{instrumentId}")
    public Object instrumentSyncState(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("instrumentId") String instrumentId,
            @RequestParam("productLine") ProductLine productLine) {
        GatewayUserIdentity.requireAdminUserId(adminUserId);
        return instrumentCoreSyncService.state(instrumentId, productLine);
    }
}
