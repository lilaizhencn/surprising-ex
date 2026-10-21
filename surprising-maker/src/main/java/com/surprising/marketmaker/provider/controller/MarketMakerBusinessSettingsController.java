package com.surprising.marketmaker.provider.controller;

import com.surprising.marketmaker.provider.exception.MarketMakerSettingsConflictException;

import com.surprising.marketmaker.provider.config.MarketMakerBusinessSettings;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.store.MarketMakerBusinessSettingsStore;
import com.surprising.marketmaker.provider.service.MarketMakerBusinessSettingsService;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import jakarta.validation.Validator;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/admin/market-maker/business-settings")
public class MarketMakerBusinessSettingsController {
    private final MarketMakerBusinessSettingsService service;
    private final MarketMakerProperties properties;
    private final Validator validator;
    private final ObjectMapper json;
    public MarketMakerBusinessSettingsController(MarketMakerBusinessSettingsService service, MarketMakerProperties properties, Validator validator, ObjectMapper json) {
        this.service = service; this.properties = properties; this.validator = validator; this.json = json;
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
        try {
            validate(request, admin);
            return service.save(request.settings(), request.expectedVersion(), admin, request.reason());
        }
        catch (MarketMakerSettingsConflictException conflict) { throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid); }
    }
    private void check(String admin, ProductLine line) {
        if (admin == null || admin.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        try { ProductLineConfiguration.requireSame(properties.getProductLine(), line, "maker settings"); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid); }
    }
    private void validate(Update request, String admin) {
        if (request == null || request.settings() == null || request.expectedVersion() <= 0 || admin == null || admin.isBlank() || request.reason() == null || request.reason().isBlank() || request.reason().length() > 1000)
            throw new IllegalArgumentException("settings, version, administrator and reason are required");
        var settings = request.settings();
        var violations = validator.validate(settings);
        if (!violations.isEmpty()) throw new IllegalArgumentException(violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().collect(java.util.stream.Collectors.joining("; ")));
        requireDuration(settings.referenceMarket().getMaxAge(), "reference maxAge", false);
        requireDuration(settings.referenceMarket().getRequestTimeout(), "reference requestTimeout", false);
        requireDuration(settings.referenceMarket().getRefreshInterval(), "reference refreshInterval", true);
        requireDuration(settings.referenceMarket().getReconnectBackoff(), "reference reconnectBackoff", false);
        requireDuration(settings.quoting().getOrderReconciliationInterval(), "orderReconciliationInterval", true);
        for (var source : settings.referenceMarket().getSources()) {
            com.surprising.product.api.utils.InstrumentIds.parse(source.getInstrumentId());
            requireUrl(source.getUrl(), false);
            var restParsers = java.util.Set.of("BINANCE_DEPTH", "BINANCE_FUTURES_DEPTH", "OKX_BOOKS", "OKX_BOOKS_FULL", "BYBIT_ORDERBOOK", "BYBIT_V5_ORDERBOOK");
            if (!restParsers.contains(source.getParser().toUpperCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("unsupported reference parser");
            if (source.getWebSocketUrl() != null && !source.getWebSocketUrl().isBlank()) {
                var websocketParsers = new java.util.HashSet<>(restParsers);
                websocketParsers.addAll(java.util.Set.of("BINANCE_DEPTH_STREAM", "BINANCE_PARTIAL_DEPTH_STREAM", "OKX_BOOKS_WS", "OKX_BOOKS_FULL_WS", "BYBIT_ORDERBOOK_WS", "BYBIT_V5_ORDERBOOK_WS"));
                if (source.getWebSocketParser() == null || !websocketParsers.contains(source.getWebSocketParser().toUpperCase(java.util.Locale.ROOT)))
                    throw new IllegalArgumentException("unsupported reference websocket parser");
            }
            if (source.getWebSocketUrl() != null && !source.getWebSocketUrl().isBlank()) requireUrl(source.getWebSocketUrl(), true);
            if (source.getWebSocketSubscribeMessage() != null && !source.getWebSocketSubscribeMessage().isBlank()) {
                try { json.readTree(source.getWebSocketSubscribeMessage()); }
                catch (RuntimeException error) { throw new IllegalArgumentException("reference subscription must be valid JSON"); }
            }
        }
    }
    private static void requireDuration(java.time.Duration value, String name, boolean zeroAllowed) {
        if (value == null || value.isNegative() || (!zeroAllowed && value.isZero()) || value.compareTo(java.time.Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException(name + " requires a positive duration at most one hour");
    }
    private static void requireUrl(String value, boolean websocket) {
        try {
            var uri = java.net.URI.create(value.replace("{externalSymbol}", "SYMBOL").replace("{externalSymbolLower}", "symbol"));
            var schemes = websocket ? java.util.Set.of("ws", "wss") : java.util.Set.of("http", "https");
            if (uri.getScheme() == null || !schemes.contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (RuntimeException error) { throw new IllegalArgumentException("invalid reference URL"); }
    }
    public record Update(MarketMakerBusinessSettings settings, long expectedVersion, String reason) {}
}
