package com.surprising.marketmaker.provider.client;

import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.marketmaker.provider.client.ReferenceLiveBook.ParsedBook;
import com.surprising.marketmaker.provider.client.ReferenceLiveBook.ParsedBookUpdate;
import com.surprising.marketmaker.provider.client.ReferenceLiveBook.ParsedLevel;
import com.surprising.marketmaker.provider.client.ReferenceLiveBook.TradeTape;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.consumer.MakerQuoteWakeups;
import com.surprising.marketmaker.provider.model.ExternalTrade;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookLevel;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;
import com.surprising.marketmaker.provider.service.ReferenceMarketProvider;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class RestReferenceMarketProvider implements ReferenceMarketProvider {

    private static final BigDecimal ONE_PPM = BigDecimal.valueOf(1_000_000L);
    private static final Duration BOOK_HOLD = Duration.ofSeconds(30);

    private final MarketMakerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final MakerQuoteWakeups wakeups;
    private final ReferenceWebSocketFeed webSocketFeed;
    private final ReentrantLock configurationLock = new ReentrantLock();
    private volatile MarketMakerProperties.ReferenceMarket loadedConfiguration;
    private volatile long configurationGeneration;

    private final Map<String, CachedSnapshot> cache = new ConcurrentHashMap<>();
    private final Map<String, ReferenceLiveBook> liveBooks = new ConcurrentHashMap<>();
    private final Map<String, TradeTape> tradeTapes = new ConcurrentHashMap<>();

    @Autowired
    public RestReferenceMarketProvider(MarketMakerProperties properties, ObjectMapper objectMapper,
                                       MakerQuoteWakeups wakeups) {
        this.properties = properties;
        this.wakeups = wakeups;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
        this.webSocketFeed = new ReferenceWebSocketFeed(properties, httpClient, objectMapper,
                this::parseWebSocketPayload);
    }

    RestReferenceMarketProvider(MarketMakerProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new MakerQuoteWakeups(properties));
    }

    @Override
    public ReferenceOrderBookSnapshot snapshot(String instrumentId, ProductLine productLine, InstrumentResponse instrument) {
        MarketMakerProperties.ReferenceMarket referenceMarket = properties.getReferenceMarket();
        long generation;
        if (loadedConfiguration != referenceMarket) {
            configurationLock.lock();
            try {
                if (loadedConfiguration != referenceMarket) {
                    webSocketFeed.reset();
                    liveBooks.clear();
                    cache.clear();
                    tradeTapes.clear();
                    loadedConfiguration = referenceMarket;
                    configurationGeneration++;
                }
                generation = configurationGeneration;
            } finally {
                configurationLock.unlock();
            }
        } else {
            generation = configurationGeneration;
        }
        if (!referenceMarket.isEnabled() || instrument == null) {
            return null;
        }
        ProductLine effectiveProductLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        String normalizedSymbol = normalizeSymbol(instrumentId);
        String cacheKey = cacheKey(effectiveProductLine, normalizedSymbol);
        Instant now = Instant.now();
        webSocketFeed.ensureConnections(referenceMarket, effectiveProductLine, normalizedSymbol, instrument, now);
        if (generation != configurationGeneration) return null;
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
                    if (generation != configurationGeneration) return null;
                    cache.put(cacheKey, new CachedSnapshot(snapshot, Instant.now(), false));
                    return snapshot;
                }
            } catch (RuntimeException ex) {
                log.debug("Reference market fetch failed source={} symbol={} error={}",
                        source.getName(), normalizedSymbol, ex.getMessage());
            }
        }

        if (generation != configurationGeneration || cached == null) return null;
        // A brief gap keeps the last two-sided book. Quoting holds it instead of pulling the ladder.
        return fresh(cached.snapshot(), now) || held(cached.snapshot(), now) ? cached.snapshot() : null;
    }

    @Override
    public List<ExternalTrade> drainTrades(String instrumentId, ProductLine productLine, int limit) {
        if (limit <= 0) return List.of();
        ProductLine effective = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        TradeTape tape = tradeTapes.get(cacheKey(effective, normalizeSymbol(instrumentId)));
        return tape == null ? List.of() : tape.drain(limit);
    }

    ReferenceOrderBookSnapshot parsePayload(MarketMakerProperties.ReferenceMarket.Source source,
                                            String instrumentId,
                                            InstrumentResponse instrument,
                                            String payload,
                                            Instant receivedAt) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            ParsedBook book = parseBook(source.getParser(), root);
            List<ReferenceOrderBookLevel> bids = convert(book.bids(), instrument, source);
            List<ReferenceOrderBookLevel> asks = convert(book.asks(), instrument, source);
            if (bids.isEmpty() || asks.isEmpty()) {
                return null;
            }
            return new ReferenceOrderBookSnapshot(source.getName(), "REST", instrumentId, bids, asks, receivedAt);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid reference order book payload: " + ex.getMessage(), ex);
        }
    }

    ReferenceOrderBookSnapshot parseWebSocketPayload(MarketMakerProperties.ReferenceMarket.Source source,
                                                     String instrumentId,
                                                     InstrumentResponse instrument,
                                                     String payload,
                                                     Instant receivedAt) {
        try {
            return parseWebSocketPayload(source, instrumentId, instrument, objectMapper.readTree(payload), receivedAt);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid reference order book websocket payload: " + ex.getMessage(), ex);
        }
    }

    private ReferenceOrderBookSnapshot parseWebSocketPayload(MarketMakerProperties.ReferenceMarket.Source source,
                                                             String instrumentId,
                                                             InstrumentResponse instrument,
                                                             JsonNode root,
                                                             Instant receivedAt) {
        try {
            JsonNode payload = root != null && root.has("stream") && root.has("data") ? root.path("data") : root;
            if (recordTrades(source, instrumentId, instrument, payload, receivedAt)) {
                return null;
            }
            ParsedBookUpdate update = parseLiveBookUpdate(liveParser(source), payload);
            if (update == null || update.empty()) {
                return null;
            }
            String normalizedSymbol = normalizeSymbol(instrumentId);
            ReferenceLiveBook liveBook = liveBooks.computeIfAbsent(liveKey(source, normalizedSymbol),
                    ignored -> new ReferenceLiveBook());
            if (update.delta()) {
                if (liveBook.isEmpty()) {
                    return null;
                }
                liveBook.applyDeltas(convertDelta(update.bids(), instrument, source), convertDelta(update.asks(), instrument, source));
            } else {
                liveBook.replace(convert(update.bids(), instrument, source), convert(update.asks(), instrument, source));
            }
            ReferenceOrderBookSnapshot snapshot = liveBook.snapshot(source.getName(), normalizedSymbol, receivedAt,
                    Math.max(1, properties.getReferenceMarket().getDepthLevels()));
            if (snapshot != null && snapshot.hasTwoSidedDepth()) {
                var previous = cache.put(cacheKey(source.getProductLine(), normalizedSymbol),
                        new CachedSnapshot(snapshot, receivedAt, true));
                if (previous == null || !previous.snapshot().bids().equals(snapshot.bids())
                        || !previous.snapshot().asks().equals(snapshot.asks())) {
                    wakeups.changed(source.getProductLine(), normalizedSymbol);
                }
                return snapshot;
            }
            return null;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid reference order book websocket payload: " + ex.getMessage(), ex);
        }
    }

    private ReferenceOrderBookSnapshot fetch(MarketMakerProperties.ReferenceMarket.Source source,
                                             String instrumentId,
                                             InstrumentResponse instrument) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(template(source.getUrl(), source)))
                    .timeout(timeout())
                    .header("User-Agent", "surprising-market-maker/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("http status " + response.statusCode());
            }
            return parsePayload(source, instrumentId, instrument, response.body(), Instant.now());
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
                    parseLevels(root.path("bids"), false), parseLevels(root.path("asks"), false));
            case "OKX_BOOKS", "OKX_BOOKS_FULL" -> {
                JsonNode book = root.path("data").path(0);
                yield new ParsedBook(parseLevels(book.path("bids"), false), parseLevels(book.path("asks"), false));
            }
            case "BYBIT_ORDERBOOK", "BYBIT_V5_ORDERBOOK" -> {
                JsonNode book = root.path("result");
                yield new ParsedBook(parseLevels(book.path("b"), false), parseLevels(book.path("a"), false));
            }
            default -> throw new IllegalArgumentException("unsupported reference market parser: " + parser);
        };
    }

    private ParsedBookUpdate parseLiveBookUpdate(String parser, JsonNode root) {
        return switch (parser.toUpperCase(Locale.ROOT)) {
            case "BINANCE_DEPTH_STREAM", "BINANCE_PARTIAL_DEPTH_STREAM" -> {
                JsonNode bids = root.has("bids") ? root.path("bids") : root.path("b");
                JsonNode asks = root.has("asks") ? root.path("asks") : root.path("a");
                yield new ParsedBookUpdate(parseLevels(bids, true), parseLevels(asks, true), false);
            }
            case "OKX_BOOKS_WS", "OKX_BOOKS_FULL_WS" -> {
                JsonNode book = root.path("data").path(0);
                boolean delta = "update".equalsIgnoreCase(root.path("action").asString("snapshot"));
                yield new ParsedBookUpdate(parseLevels(book.path("bids"), true),
                        parseLevels(book.path("asks"), true), delta);
            }
            case "BYBIT_ORDERBOOK_WS", "BYBIT_V5_ORDERBOOK_WS" -> {
                JsonNode book = root.path("data");
                boolean delta = "delta".equalsIgnoreCase(root.path("type").asString("snapshot"));
                yield new ParsedBookUpdate(parseLevels(book.path("b"), true),
                        parseLevels(book.path("a"), true), delta);
            }
            default -> {
                ParsedBook book = parseBook(parser, root);
                yield new ParsedBookUpdate(book.bids(), book.asks(), false);
            }
        };
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

    private List<ReferenceOrderBookLevel> convertDelta(List<ParsedLevel> levels, InstrumentResponse instrument,
                                                       MarketMakerProperties.ReferenceMarket.Source source) {
        List<ReferenceOrderBookLevel> result = new ArrayList<>();
        var referenceMarket = properties.getReferenceMarket();
        for (ParsedLevel level : levels) {
            long priceTicks = toTicks(level.price(), instrument.pricePrecision());
            long quantitySteps = toSteps(level.quantity(), instrument, source, referenceMarket);
            if (priceTicks > 0) {
                result.add(new ReferenceOrderBookLevel(priceTicks, quantitySteps));
            }
        }
        return result;
    }

    private List<ReferenceOrderBookLevel> convert(List<ParsedLevel> levels, InstrumentResponse instrument,
                                                  MarketMakerProperties.ReferenceMarket.Source source) {
        List<ReferenceOrderBookLevel> result = new ArrayList<>();
        var referenceMarket = properties.getReferenceMarket();
        for (ParsedLevel level : levels) {
            long priceTicks = toTicks(level.price(), instrument.pricePrecision());
            long quantitySteps = toSteps(level.quantity(), instrument, source, referenceMarket);
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

    private long toSteps(BigDecimal quantity, InstrumentResponse instrument,
                         MarketMakerProperties.ReferenceMarket.Source source,
                         MarketMakerProperties.ReferenceMarket referenceMarket) {
        BigDecimal size = quantity;
        if (instrument.contractType() == com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL
                || instrument.contractType() == com.surprising.instrument.api.model.ContractType.LINEAR_DELIVERY) {
            size = size.divide(BigDecimal.valueOf(instrument.contractMultiplierPpm()), 18, RoundingMode.HALF_UP)
                    .multiply(ONE_PPM);
        } else {
            size = size.movePointRight(Math.max(0, instrument.quantityPrecision()));
        }
        long rawSteps = size
                .multiply(BigDecimal.valueOf(Math.max(1L, referenceMarket.getQuantityScalePpm())))
                .multiply(BigDecimal.valueOf(source.getQuantityScalePpm()))
                .divide(ONE_PPM.multiply(ONE_PPM), 0, RoundingMode.HALF_UP)
                .longValueExact();
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
        String value = node.path(index).asString(null);
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }

    /** OKX & Binance public trades share the socket with the book. The taker side is the print side. */
    private boolean recordTrades(MarketMakerProperties.ReferenceMarket.Source source, String instrumentId,
                                 InstrumentResponse instrument, JsonNode root, Instant receivedAt) {
        if (root == null) return false;
        if ("trade".equalsIgnoreCase(root.path("e").asString(""))) {
            String eventSymbol = root.path("s").asString("");
            if (hasText(source.getExternalSymbol()) && hasText(eventSymbol)
                    && !source.getExternalSymbol().equalsIgnoreCase(eventSymbol)) {
                return false;
            }
            OrderSide side = root.path("m").asBoolean(false) ? OrderSide.SELL : OrderSide.BUY;
            long priceTicks = toTicks(decimalText(root.path("p").asString(null)), instrument.pricePrecision());
            long quantitySteps = toSteps(decimalText(root.path("q").asString(null)), instrument, source,
                    properties.getReferenceMarket());
            if (priceTicks > 0 && quantitySteps > 0) {
                String key = cacheKey(source.getProductLine(), normalizeSymbol(instrumentId));
                TradeTape tape = tradeTapes.computeIfAbsent(key, ignored -> new TradeTape());
                String tradeId = root.path("t").asString("");
                tape.add(new ExternalTrade(tradeId.isBlank() ? priceTicks + ":" + receivedAt.toEpochMilli() : tradeId,
                        side, priceTicks, quantitySteps, receivedAt));
            }
            return true;
        }
        if (!"trades".equalsIgnoreCase(root.path("arg").path("channel").asString(""))) {
            return false;
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) return true;
        String key = cacheKey(source.getProductLine(), normalizeSymbol(instrumentId));
        TradeTape tape = tradeTapes.computeIfAbsent(key, ignored -> new TradeTape());
        for (JsonNode trade : data) {
            OrderSide side = "buy".equalsIgnoreCase(trade.path("side").asString("")) ? OrderSide.BUY
                    : "sell".equalsIgnoreCase(trade.path("side").asString("")) ? OrderSide.SELL : null;
            if (side == null) continue;
            long priceTicks = toTicks(decimalText(trade.path("px").asString(null)), instrument.pricePrecision());
            long quantitySteps = toSteps(decimalText(trade.path("sz").asString(null)), instrument, source,
                    properties.getReferenceMarket());
            if (priceTicks <= 0 || quantitySteps <= 0) continue;
            String tradeId = trade.path("tradeId").asString("");
            tape.add(new ExternalTrade(tradeId.isBlank() ? priceTicks + ":" + receivedAt.toEpochMilli() : tradeId,
                    side, priceTicks, quantitySteps, receivedAt));
        }
        return true;
    }

    private BigDecimal decimalText(String value) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO;
        return new BigDecimal(value);
    }

    private boolean fresh(ReferenceOrderBookSnapshot snapshot, Instant now) {
        return snapshot != null
                && snapshot.receivedAt() != null
                && snapshot.receivedAt().plus(maxAge()).isAfter(now);
    }

    private boolean held(ReferenceOrderBookSnapshot snapshot, Instant now) {
        return snapshot != null
                && snapshot.receivedAt() != null
                && snapshot.receivedAt().plus(holdAge()).isAfter(now);
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

    private Duration holdAge() {
        Duration maxAge = maxAge();
        return maxAge.compareTo(BOOK_HOLD) > 0 ? maxAge : BOOK_HOLD;
    }

    private String liveParser(MarketMakerProperties.ReferenceMarket.Source source) {
        return hasText(source.getWebSocketParser()) ? source.getWebSocketParser() : source.getParser();
    }

    static boolean matches(MarketMakerProperties.ReferenceMarket.Source source, ProductLine productLine, String instrumentId) {
        return source != null
                && source.isEnabled()
                && source.getProductLine() == productLine
                && instrumentId.equals(normalizeSymbol(source.getInstrumentId()));
    }

    static String template(String value, MarketMakerProperties.ReferenceMarket.Source source) {
        String externalSymbol = source.getExternalSymbol();
        return value
                .replace("{externalSymbol}", externalSymbol)
                .replace("{externalSymbolLower}", externalSymbol.toLowerCase(Locale.ROOT));
    }

    static String liveKey(MarketMakerProperties.ReferenceMarket.Source source, String instrumentId) {
        return source.getProductLine().name() + ":" + normalizeSymbol(source.getName()) + ":" + normalizeSymbol(instrumentId);
    }

    static String cacheKey(ProductLine productLine, String instrumentId) {
        return (productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine).name() + ":" + normalizeSymbol(instrumentId);
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static String normalizeSymbol(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record CachedSnapshot(ReferenceOrderBookSnapshot snapshot, Instant fetchedAt, boolean streaming) {
    }
}
