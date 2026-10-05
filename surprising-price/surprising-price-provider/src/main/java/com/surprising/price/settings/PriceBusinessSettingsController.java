package com.surprising.price.settings;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/price/index/admin/business-settings")
public class PriceBusinessSettingsController {
    private final PriceBusinessSettingsService settings;
    public PriceBusinessSettingsController(PriceBusinessSettingsService settings) { this.settings = settings; }
    @GetMapping public Map<String, Object> current(@RequestHeader("X-Admin-User-Id") String admin) {
        return Map.of("config", settings.current(), "fields", settings.fields());
    }
    @PostMapping public PriceBusinessSettingsService.Snapshot update(@RequestHeader("X-Admin-User-Id") String admin, @RequestBody Update request) {
        try { return settings.save(request.settings(), request.expectedVersion(), admin, request.reason()); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex); }
    }
    public record Update(Map<String, JsonNode> settings, Long expectedVersion, String reason) {}
}
