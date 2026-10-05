package com.surprising.marketmaker.provider.controller;

import com.surprising.marketmaker.provider.config.MarketMakerBusinessSettings;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.repository.MarketMakerBusinessSettingsStore;
import com.surprising.marketmaker.provider.service.MarketMakerBusinessSettingsService;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/admin/market-maker/business-settings")
public class MarketMakerBusinessSettingsController {
    private final MarketMakerBusinessSettingsService service;
    private final MarketMakerProperties properties;
    public MarketMakerBusinessSettingsController(MarketMakerBusinessSettingsService service, MarketMakerProperties properties) {
        this.service = service; this.properties = properties;
    }
    @GetMapping
    public MarketMakerBusinessSettingsStore.Settings get(@RequestHeader("X-Admin-User-Id") String admin,
                                                          @RequestParam ProductLine productLine) {
        check(admin, productLine);
        return service.current();
    }
    @PostMapping
    public MarketMakerBusinessSettingsStore.Settings save(@RequestHeader("X-Admin-User-Id") String admin,
            @RequestParam ProductLine productLine, @RequestBody Update request) {
        check(admin, productLine);
        try { return service.save(request.settings(), request.expectedVersion(), admin, request.reason()); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid); }
    }
    private void check(String admin, ProductLine line) {
        if (admin == null || admin.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        try { ProductLineConfiguration.requireSame(properties.getProductLine(), line, "maker settings"); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid); }
    }
    public record Update(MarketMakerBusinessSettings settings, long expectedVersion, String reason) {}
}
