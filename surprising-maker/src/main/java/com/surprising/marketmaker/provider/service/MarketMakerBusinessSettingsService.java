package com.surprising.marketmaker.provider.service;

import com.surprising.marketmaker.provider.config.MarketMakerBusinessSettings;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.repository.MarketMakerBusinessSettingsStore;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

@Service
public class MarketMakerBusinessSettingsService {
    private final MarketMakerProperties properties;
    private final MarketMakerBusinessSettingsStore store;
    private final Validator validator;
    public MarketMakerBusinessSettingsService(MarketMakerProperties properties, MarketMakerBusinessSettingsStore store, Validator validator) {
        this.properties = properties; this.store = store; this.validator = validator;
    }
    public MarketMakerBusinessSettingsStore.Settings current() { return store.load(properties.getProductLine()); }
    public synchronized MarketMakerBusinessSettingsStore.Settings save(MarketMakerBusinessSettings settings, long version, String admin, String reason) {
        if (settings == null || version <= 0 || admin == null || admin.isBlank() || reason == null || reason.isBlank() || reason.length() > 1000)
            throw new IllegalArgumentException("settings, version, administrator and reason are required");
        var violations = validator.validate(settings);
        if (!violations.isEmpty()) throw new IllegalArgumentException(violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().collect(java.util.stream.Collectors.joining("; ")));
        var candidate = new MarketMakerProperties();
        candidate.setProductLine(properties.getProductLine());
        candidate.install(settings);
        candidate.validateBusinessSettings();
        requireDuration(settings.referenceMarket().getMaxAge(), "reference maxAge", false);
        requireDuration(settings.referenceMarket().getRequestTimeout(), "reference requestTimeout", false);
        requireDuration(settings.referenceMarket().getRefreshInterval(), "reference refreshInterval", true);
        requireDuration(settings.referenceMarket().getReconnectBackoff(), "reference reconnectBackoff", false);
        requireDuration(settings.quoting().getOrderReconciliationInterval(), "orderReconciliationInterval", true);
        if (settings.referenceMarket().getMaxQuantitySteps() < settings.referenceMarket().getMinQuantitySteps()
                || settings.trade().getMaxQuantitySteps() < settings.trade().getMinQuantitySteps())
            throw new IllegalArgumentException("maximum quantity must not be below minimum quantity");
        for (var source : settings.referenceMarket().getSources()) {
            com.surprising.product.api.InstrumentIds.parse(source.getInstrumentId());
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
                try { new tools.jackson.databind.ObjectMapper().readTree(source.getWebSocketSubscribeMessage()); }
                catch (RuntimeException error) { throw new IllegalArgumentException("reference subscription must be valid JSON"); }
            }
        }
        var saved = store.save(properties.getProductLine(), settings, version, admin, reason);
        properties.install(saved.settings());
        return saved;
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
}
