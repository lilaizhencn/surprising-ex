package com.surprising.marketmaker.provider.service;

import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookLevel;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RestReferenceMarketProvider implements ReferenceMarketProvider {

    private static final Logger log = LoggerFactory.getLogger(RestReferenceMarketProvider.class);
    private static final BigDecimal ONE_PPM = BigDecimal.valueOf(1_000_000L);

    private final MarketMakerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, CachedSnapshot> cache = new ConcurrentHashMap<>();
    private final Map<String, LiveBook> liveBooks = new ConcurrentHashMap<>();
    private final Map<String, WebSocketState> webSockets = new ConcurrentHashMap<>();

    public RestReferenceMarketProvider(MarketMakerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
    }

    @Override
    public ReferenceOrderBookSnapshot snapshot(String symbol, ProductLine productLine, InstrumentResponse instrument) {
        MarketMakerProperties.ReferenceMarket referenceMarket = properties.getReferenceMarket();
        if (!referenceMarket.isEnabled() || instrument == null) {
            return null;
        }
        ProductLine effectiveProductLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        String normalizedSymbol = normalizeSymbol(symbol);
        String cacheKey = cacheKey(effectiveProductLine, normalizedSymbol);
        Instant now = Instant.now();
        ensureWebSocketConnections(referenceMarket, effectiveProductLine, normalizedSymbol, instrument, now);
        CachedSnapshot cached = cache.get(cacheKey);
        if (cached != null
                && (cached.streaming() || cached.fetchedAt().plus(refreshInterval()).isAfter(now))
                && fresh(cached.snapshot(), now)) {
            return cached.snapshot();
        }

        for (MarketMakerProperties.ReferenceMarket.Source source : referenceMarket.getSources()) {
            if (!matches(source, effectiveProductLine, normalizedSymbol)) {
                continue;
            }
            try {
                ReferenceOrderBookSnapshot snapshot = fetch(source, normalizedSymbol, instrument);
                if (snapshot != null && snapshot.hasTwoSidedDepth()) {
                    cache.put(cacheKey, new CachedSnapshot(snapshot, Instant.now(), false));
                    return snapshot;
                }
            } catch (RuntimeException ex) {
                log.debug("Reference market fetch failed source={} symbol={} error={}",
                        source.getName(), normalizedSymbol, ex.getMessage());
            }
        }

        if (cached != null && fresh(cached.snapshot(), now)) {
            return cached.snapshot();
        }
        return null;
    }

    ReferenceOrderBookSnapshot parsePayload(MarketMakerProperties.ReferenceMarket.Source source,
                                            String symbol,
                                            InstrumentResponse instrument,
                                            String payload,
                                            Instant receivedAt) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            ParsedBook book = parseBook(source.getParser(), root);
            List<ReferenceOrderBookLevel> bids = convert(book.bids(), instrument);
            List<ReferenceOrderBookLevel> asks = convert(book.asks(), instrument);
            if (bids.isEmpty() || asks.isEmpty()) {
                return null;
            }
            return new ReferenceOrderBookSnapshot(source.getName(), "REST", symbol, bids, asks, receivedAt);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid reference order book payload: " + ex.getMessage(), ex);
        }
    }

    ReferenceOrderBookSnapshot parseWebSocketPayload(MarketMakerProperties.ReferenceMarket.Source source,
                                                     String symbol,
                                                     InstrumentResponse instrument,
                                                     String payload,
                                                     Instant receivedAt) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            ParsedBookUpdate update = parseLiveBookUpdate(liveParser(source), root);
            if (update == null || update.empty()) {
                return null;
            }
            String normalizedSymbol = normalizeSymbol(symbol);
            LiveBook liveBook = liveBooks.computeIfAbsent(liveKey(source, normalizedSymbol), ignored -> new LiveBook());
            if (update.delta()) {
                if (liveBook.isEmpty()) {
                    return null;
                }
                liveBook.applyDeltas(convertDelta(update.bids(), instrument), convertDelta(update.asks(), instrument));
            } else {
                liveBook.replace(convert(update.bids(), instrument), convert(update.asks(), instrument));
            }
            ReferenceOrderBookSnapshot snapshot = liveBook.snapshot(source.getName(), normalizedSymbol, receivedAt,
                    Math.max(1, properties.getReferenceMarket().getDepthLevels()));
            if (snapshot != null && snapshot.hasTwoSidedDepth()) {
                cache.put(cacheKey(source.getProductLine(), normalizedSymbol), new CachedSnapshot(snapshot, receivedAt, true));
                return snapshot;
            }
            return null;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid reference order book websocket payload: " + ex.getMessage(), ex);
        }
    }

    private ReferenceOrderBookSnapshot fetch(MarketMakerProperties.ReferenceMarket.Source source,
                                             String symbol,
                                             InstrumentResponse instrument) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url(source)))
                    .timeout(timeout())
                    .header("User-Agent", "surprising-market-maker/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("http status " + response.statusCode());
            }
            return parsePayload(source, symbol, instrument, response.body(), Instant.now());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("reference market request interrupted", ex);
        } catch (Exception ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    private ParsedBook parseBook(String parser, JsonNode root) {
        return switch (parser.toUpperCase(Locale.ROOT)) {
            case "BINANCE_DEPTH", "BINANCE_FUTURES_DEPTH" -> new ParsedBook(
                    parseLevels(root.path("bids")), parseLevels(root.path("asks")));
            case "OKX_BOOKS", "OKX_BOOKS_FULL" -> {
                JsonNode book = root.path("data").path(0);
                yield new ParsedBook(parseLevels(book.path("bids")), parseLevels(book.path("asks")));
            }
            case "BYBIT_ORDERBOOK", "BYBIT_V5_ORDERBOOK" -> {
                JsonNode book = root.path("result");
                yield new ParsedBook(parseLevels(book.path("b")), parseLevels(book.path("a")));
            }
            default -> throw new IllegalArgumentException("unsupported reference market parser: " + parser);
        };
    }

    private ParsedBookUpdate parseLiveBookUpdate(String parser, JsonNode root) {
        return switch (parser.toUpperCase(Locale.ROOT)) {
            case "BINANCE_DEPTH_STREAM", "BINANCE_PARTIAL_DEPTH_STREAM" -> new ParsedBookUpdate(
                    parseLevels(root.path("b"), true), parseLevels(root.path("a"), true), false);
            case "OKX_BOOKS_WS", "OKX_BOOKS_FULL_WS" -> {
                JsonNode book = root.path("data").path(0);
                boolean delta = "update".equalsIgnoreCase(root.path("action").asText("snapshot"));
                yield new ParsedBookUpdate(parseLevels(book.path("bids"), true),
                        parseLevels(book.path("asks"), true), delta);
            }
            case "BYBIT_ORDERBOOK_WS", "BYBIT_V5_ORDERBOOK_WS" -> {
                JsonNode book = root.path("data");
                boolean delta = "delta".equalsIgnoreCase(root.path("type").asText("snapshot"));
                yield new ParsedBookUpdate(parseLevels(book.path("b"), true),
                        parseLevels(book.path("a"), true), delta);
            }
            default -> {
                ParsedBook book = parseBook(parser, root);
                yield new ParsedBookUpdate(book.bids(), book.asks(), false);
            }
        };
    }

    private List<ParsedLevel> parseLevels(JsonNode levels) {
        return parseLevels(levels, false);
    }

    private List<ParsedLevel> parseLevels(JsonNode levels, boolean includeZeroQuantity) {
        List<ParsedLevel> result = new ArrayList<>();
        if (levels == null || !levels.isArray()) {
            return result;
        }
        int maxDepth = Math.max(1, properties.getReferenceMarket().getDepthLevels());
        for (JsonNode level : levels) {
            if (result.size() >= maxDepth) {
                break;
            }
            BigDecimal price = decimal(level, 0);
            BigDecimal quantity = decimal(level, 1);
            if (price.signum() > 0 && (includeZeroQuantity ? quantity.signum() >= 0 : quantity.signum() > 0)) {
                result.add(new ParsedLevel(price, quantity));
            }
        }
        return result;
    }

    private List<ReferenceOrderBookLevel> convertDelta(List<ParsedLevel> levels, InstrumentResponse instrument) {
        List<ReferenceOrderBookLevel> result = new ArrayList<>();
        for (ParsedLevel level : levels) {
            long priceTicks = toTicks(level.price(), instrument.pricePrecision());
            long quantitySteps = toSteps(level.quantity(), instrument.quantityPrecision());
            if (priceTicks > 0) {
                result.add(new ReferenceOrderBookLevel(priceTicks, quantitySteps));
            }
        }
        return result;
    }

    private List<ReferenceOrderBookLevel> convert(List<ParsedLevel> levels, InstrumentResponse instrument) {
        List<ReferenceOrderBookLevel> result = new ArrayList<>();
        for (ParsedLevel level : levels) {
            long priceTicks = toTicks(level.price(), instrument.pricePrecision());
            long quantitySteps = toSteps(level.quantity(), instrument.quantityPrecision());
            if (priceTicks > 0 && quantitySteps > 0) {
                result.add(new ReferenceOrderBookLevel(priceTicks, quantitySteps));
            }
        }
        return result;
    }

    private long toTicks(BigDecimal price, int pricePrecision) {
        return price.movePointRight(Math.max(0, pricePrecision))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private long toSteps(BigDecimal quantity, int quantityPrecision) {
        long rawSteps = quantity.movePointRight(Math.max(0, quantityPrecision))
                .multiply(BigDecimal.valueOf(Math.max(1L, properties.getReferenceMarket().getQuantityScalePpm())))
                .divide(ONE_PPM, 0, RoundingMode.HALF_UP)
                .longValueExact();
        MarketMakerProperties.ReferenceMarket referenceMarket = properties.getReferenceMarket();
        long minQuantity = Math.max(1L, referenceMarket.getMinQuantitySteps());
        long maxQuantity = Math.max(minQuantity, referenceMarket.getMaxQuantitySteps());
        if (rawSteps <= 0) {
            return 0L;
        }
        return Math.max(minQuantity, Math.min(maxQuantity, rawSteps));
    }

    private BigDecimal decimal(JsonNode node, int index) {
        if (node == null || !node.isArray() || node.size() <= index) {
            return BigDecimal.ZERO;
        }
        String value = node.path(index).asText(null);
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }

    private boolean matches(MarketMakerProperties.ReferenceMarket.Source source, ProductLine productLine, String symbol) {
        return source != null
                && source.isEnabled()
                && source.getProductLine() == productLine
                && symbol.equals(normalizeSymbol(source.getSymbol()));
    }

    private boolean fresh(ReferenceOrderBookSnapshot snapshot, Instant now) {
        Duration maxAge = maxAge();
        return snapshot != null
                && snapshot.receivedAt() != null
                && snapshot.receivedAt().plus(maxAge).isAfter(now);
    }

    private void ensureWebSocketConnections(MarketMakerProperties.ReferenceMarket referenceMarket,
                                            ProductLine productLine,
                                            String symbol,
                                            InstrumentResponse instrument,
                                            Instant now) {
        if (!referenceMarket.isWebSocketEnabled()) {
            return;
        }
        for (MarketMakerProperties.ReferenceMarket.Source source : referenceMarket.getSources()) {
            if (!matches(source, productLine, symbol) || !hasText(source.getWebSocketUrl())) {
                continue;
            }
            String liveKey = liveKey(source, symbol);
            WebSocketState state = webSockets.computeIfAbsent(liveKey, ignored -> new WebSocketState());
            if (!shouldConnect(state, now)) {
                continue;
            }
            state.connecting = true;
            state.lastAttempt = now;
            try {
                httpClient.newWebSocketBuilder()
                        .connectTimeout(timeout())
                        .buildAsync(URI.create(template(source.getWebSocketUrl(), source)),
                                new ReferenceMarketWebSocketListener(source, symbol, instrument, state))
                        .whenComplete((webSocket, error) -> {
                            if (error != null) {
                                state.connecting = false;
                                state.webSocket = null;
                                log.debug("Reference market websocket connect failed source={} symbol={} error={}",
                                        source.getName(), symbol, error.getMessage());
                            }
                        });
            } catch (RuntimeException ex) {
                state.connecting = false;
                state.webSocket = null;
                log.debug("Reference market websocket connect rejected source={} symbol={} error={}",
                        source.getName(), symbol, ex.getMessage());
            }
        }
    }

    private boolean shouldConnect(WebSocketState state, Instant now) {
        WebSocket webSocket = state.webSocket;
        if (state.connecting || (webSocket != null && !webSocket.isInputClosed() && !webSocket.isOutputClosed())) {
            return false;
        }
        return state.lastAttempt.plus(reconnectBackoff()).isBefore(now);
    }

    private String url(MarketMakerProperties.ReferenceMarket.Source source) {
        return template(source.getUrl(), source);
    }

    private String template(String value, MarketMakerProperties.ReferenceMarket.Source source) {
        String externalSymbol = source.getExternalSymbol();
        return value
                .replace("{symbol}", externalSymbol)
                .replace("{externalSymbol}", externalSymbol)
                .replace("{externalSymbolLower}", externalSymbol.toLowerCase(Locale.ROOT));
    }

    private Duration timeout() {
        Duration timeout = properties.getReferenceMarket().getRequestTimeout();
        return timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(2) : timeout;
    }

    private Duration refreshInterval() {
        Duration refreshInterval = properties.getReferenceMarket().getRefreshInterval();
        return refreshInterval == null || refreshInterval.isNegative() ? Duration.ZERO : refreshInterval;
    }

    private Duration maxAge() {
        Duration maxAge = properties.getReferenceMarket().getMaxAge();
        return maxAge == null || maxAge.isNegative() || maxAge.isZero() ? Duration.ofSeconds(3) : maxAge;
    }

    private Duration reconnectBackoff() {
        Duration reconnectBackoff = properties.getReferenceMarket().getReconnectBackoff();
        return reconnectBackoff == null || reconnectBackoff.isNegative() || reconnectBackoff.isZero()
                ? Duration.ofSeconds(5)
                : reconnectBackoff;
    }

    private String liveParser(MarketMakerProperties.ReferenceMarket.Source source) {
        return hasText(source.getWebSocketParser()) ? source.getWebSocketParser() : source.getParser();
    }

    private String liveKey(MarketMakerProperties.ReferenceMarket.Source source, String symbol) {
        return source.getProductLine().name() + ":" + normalizeSymbol(source.getName()) + ":" + normalizeSymbol(symbol);
    }

    private String cacheKey(ProductLine productLine, String symbol) {
        return (productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine).name() + ":" + normalizeSymbol(symbol);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeSymbol(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record CachedSnapshot(ReferenceOrderBookSnapshot snapshot, Instant fetchedAt, boolean streaming) {
    }

    private static final class WebSocketState {
        private volatile WebSocket webSocket;
        private volatile boolean connecting;
        private volatile Instant lastAttempt = Instant.EPOCH;
    }

    private final class ReferenceMarketWebSocketListener implements WebSocket.Listener {
        private final MarketMakerProperties.ReferenceMarket.Source source;
        private final String symbol;
        private final InstrumentResponse instrument;
        private final WebSocketState state;
        private final StringBuilder text = new StringBuilder();

        private ReferenceMarketWebSocketListener(MarketMakerProperties.ReferenceMarket.Source source,
                                                 String symbol,
                                                 InstrumentResponse instrument,
                                                 WebSocketState state) {
            this.source = source;
            this.symbol = symbol;
            this.instrument = instrument;
            this.state = state;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            state.webSocket = webSocket;
            state.connecting = false;
            String subscribeMessage = source.getWebSocketSubscribeMessage();
            if (hasText(subscribeMessage)) {
                webSocket.sendText(template(subscribeMessage, source), true);
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String payload = text.toString();
                text.setLength(0);
                try {
                    parseWebSocketPayload(source, symbol, instrument, payload, Instant.now());
                } catch (IllegalArgumentException ex) {
                    log.debug("Reference market websocket message ignored source={} symbol={} error={}",
                            source.getName(), symbol, ex.getMessage());
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            state.webSocket = null;
            state.connecting = false;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            state.webSocket = null;
            state.connecting = false;
            log.debug("Reference market websocket failed source={} symbol={} error={}",
                    source.getName(), symbol, error.getMessage());
        }
    }

    private static final class LiveBook {
        private final NavigableMap<Long, Long> bids = new TreeMap<>(Comparator.reverseOrder());
        private final NavigableMap<Long, Long> asks = new TreeMap<>();

        synchronized boolean isEmpty() {
            return bids.isEmpty() || asks.isEmpty();
        }

        synchronized void replace(List<ReferenceOrderBookLevel> bidLevels,
                                  List<ReferenceOrderBookLevel> askLevels) {
            bids.clear();
            asks.clear();
            putAll(bids, bidLevels);
            putAll(asks, askLevels);
        }

        synchronized void applyDeltas(List<ReferenceOrderBookLevel> bidLevels,
                                      List<ReferenceOrderBookLevel> askLevels) {
            applyDelta(bids, bidLevels);
            applyDelta(asks, askLevels);
        }

        synchronized ReferenceOrderBookSnapshot snapshot(String source,
                                                         String symbol,
                                                         Instant receivedAt,
                                                         int depthLevels) {
            List<ReferenceOrderBookLevel> bidLevels = topLevels(bids, depthLevels);
            List<ReferenceOrderBookLevel> askLevels = topLevels(asks, depthLevels);
            if (bidLevels.isEmpty() || askLevels.isEmpty()) {
                return null;
            }
            return new ReferenceOrderBookSnapshot(source, "WEBSOCKET", symbol, bidLevels, askLevels, receivedAt);
        }

        private void putAll(NavigableMap<Long, Long> side, List<ReferenceOrderBookLevel> levels) {
            for (ReferenceOrderBookLevel level : levels) {
                if (level.priceTicks() > 0 && level.quantitySteps() > 0) {
                    side.put(level.priceTicks(), level.quantitySteps());
                }
            }
        }

        private void applyDelta(NavigableMap<Long, Long> side, List<ReferenceOrderBookLevel> levels) {
            for (ReferenceOrderBookLevel level : levels) {
                if (level.priceTicks() <= 0) {
                    continue;
                }
                if (level.quantitySteps() <= 0) {
                    side.remove(level.priceTicks());
                } else {
                    side.put(level.priceTicks(), level.quantitySteps());
                }
            }
        }

        private List<ReferenceOrderBookLevel> topLevels(NavigableMap<Long, Long> side, int depthLevels) {
            List<ReferenceOrderBookLevel> levels = new ArrayList<>();
            for (Map.Entry<Long, Long> entry : side.entrySet()) {
                if (levels.size() >= depthLevels) {
                    break;
                }
                levels.add(new ReferenceOrderBookLevel(entry.getKey(), entry.getValue()));
            }
            return levels;
        }
    }

    private record ParsedBook(List<ParsedLevel> bids, List<ParsedLevel> asks) {
    }

    private record ParsedBookUpdate(List<ParsedLevel> bids, List<ParsedLevel> asks, boolean delta) {
        private boolean empty() {
            return bids.isEmpty() && asks.isEmpty();
        }
    }

    private record ParsedLevel(BigDecimal price, BigDecimal quantity) {
    }
}
