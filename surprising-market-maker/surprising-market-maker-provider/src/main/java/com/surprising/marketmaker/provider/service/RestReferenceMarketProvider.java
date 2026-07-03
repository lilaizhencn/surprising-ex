package com.surprising.marketmaker.provider.service;

import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookLevel;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;
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

    public RestReferenceMarketProvider(MarketMakerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
    }

    @Override
    public ReferenceOrderBookSnapshot snapshot(String symbol, InstrumentResponse instrument) {
        MarketMakerProperties.ReferenceMarket referenceMarket = properties.getReferenceMarket();
        if (!referenceMarket.isEnabled() || instrument == null) {
            return null;
        }
        String normalizedSymbol = normalizeSymbol(symbol);
        Instant now = Instant.now();
        CachedSnapshot cached = cache.get(normalizedSymbol);
        if (cached != null
                && cached.fetchedAt().plus(refreshInterval()).isAfter(now)
                && fresh(cached.snapshot(), now)) {
            return cached.snapshot();
        }

        for (MarketMakerProperties.ReferenceMarket.Source source : referenceMarket.getSources()) {
            if (!matches(source, normalizedSymbol)) {
                continue;
            }
            try {
                ReferenceOrderBookSnapshot snapshot = fetch(source, normalizedSymbol, instrument);
                if (snapshot != null && snapshot.hasTwoSidedDepth()) {
                    cache.put(normalizedSymbol, new CachedSnapshot(snapshot, Instant.now()));
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
            return new ReferenceOrderBookSnapshot(source.getName(), symbol, bids, asks, receivedAt);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid reference order book payload: " + ex.getMessage(), ex);
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

    private List<ParsedLevel> parseLevels(JsonNode levels) {
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
            if (price.signum() > 0 && quantity.signum() > 0) {
                result.add(new ParsedLevel(price, quantity));
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

    private boolean matches(MarketMakerProperties.ReferenceMarket.Source source, String symbol) {
        return source != null
                && source.isEnabled()
                && symbol.equals(normalizeSymbol(source.getSymbol()));
    }

    private boolean fresh(ReferenceOrderBookSnapshot snapshot, Instant now) {
        Duration maxAge = maxAge();
        return snapshot != null
                && snapshot.receivedAt() != null
                && snapshot.receivedAt().plus(maxAge).isAfter(now);
    }

    private String url(MarketMakerProperties.ReferenceMarket.Source source) {
        return source.getUrl()
                .replace("{symbol}", source.getExternalSymbol())
                .replace("{externalSymbol}", source.getExternalSymbol());
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

    private String normalizeSymbol(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record CachedSnapshot(ReferenceOrderBookSnapshot snapshot, Instant fetchedAt) {
    }

    private record ParsedBook(List<ParsedLevel> bids, List<ParsedLevel> asks) {
    }

    private record ParsedLevel(BigDecimal price, BigDecimal quantity) {
    }
}
