package com.surprising.price.index.client;

import com.surprising.price.api.model.SourceStatus;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.model.SourceQuote;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ExternalSpotPriceClient {

    private final IndexPriceProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final HttpClient httpClient;
    private final Map<String, CachedConversionRate> conversionRateCache = new ConcurrentHashMap<>();

    public ExternalSpotPriceClient(IndexPriceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executorService = Executors.newFixedThreadPool(properties.getHttp().getMaxConcurrentRequests());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getHttp().getConnectTimeout())
                .executor(executorService)
                .build();
    }

    public CompletableFuture<SourceQuote> fetch(IndexPriceProperties.SourceConfig source) {
        if (!source.isEnabled()) {
            return CompletableFuture.completedFuture(error(source, SourceStatus.DISABLED, "source disabled", Instant.now(), null));
        }

        Instant start = Instant.now();
        HttpRequest request = HttpRequest.newBuilder(URI.create(source.getBaseUrl() + source.getPath()))
                .timeout(properties.getHttp().getRequestTimeout())
                .header("User-Agent", properties.getHttp().getUserAgent())
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(source, response, start))
                .exceptionally(ex -> error(source, SourceStatus.ERROR, ex.getMessage(), Instant.now(), elapsedMillis(start, Instant.now())));
    }

    public SourceQuote parsePayload(IndexPriceProperties.SourceConfig source, String payload,
                                    Instant receivedAt, Long latencyMillis) {
        try {
            return toSourceQuote(source, payload, receivedAt, latencyMillis, false);
        } catch (Exception ex) {
            return error(source, SourceStatus.ERROR, ex.getMessage(), receivedAt, latencyMillis);
        }
    }

    public Optional<SourceQuote> parseWebSocketPayload(IndexPriceProperties.SourceConfig source, String payload,
                                                       Instant receivedAt) {
        try {
            return Optional.of(toSourceQuote(source, payload, receivedAt, null, true));
        } catch (IgnoredPayloadException ex) {
            return Optional.empty();
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public BigDecimal fetchTickerPrice(String baseUrl, String path, String parser) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(properties.getHttp().getRequestTimeout())
                .header("User-Agent", properties.getHttp().getUserAgent())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException("ticker http status " + response.statusCode());
        }
        ParsedTicker ticker = parseTicker(parser, objectMapper.readTree(response.body()));
        return ticker.price();
    }

    @PreDestroy
    public void close() {
        executorService.shutdownNow();
    }

    private SourceQuote parseResponse(IndexPriceProperties.SourceConfig source, HttpResponse<String> response, Instant start) {
        Instant receivedAt = Instant.now();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return error(source, SourceStatus.ERROR, "http status " + response.statusCode(), receivedAt, elapsedMillis(start, receivedAt));
        }
        return parsePayload(source, response.body(), receivedAt, elapsedMillis(start, receivedAt));
    }

    private SourceQuote toSourceQuote(IndexPriceProperties.SourceConfig source, String payload, Instant receivedAt,
                                      Long latencyMillis, boolean websocket) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        ParsedTicker ticker = parseTicker(parser(source, websocket), root);
        if (websocket && ticker.instrument() != null && !sameInstrument(ticker.instrument(), source.getSourceSymbol())) {
            throw new IgnoredPayloadException();
        }
        ConvertedTicker converted = convertToTargetQuote(source, ticker);
        BigDecimal configuredWeight = source.getWeight().multiply(converted.weightMultiplier());
        return new SourceQuote(source.getName(), source.getSourceSymbol(), converted.price(), converted.bidPrice(),
                converted.askPrice(), configuredWeight, SourceStatus.HEALTHY, converted.reason(),
                ticker.sourceTime(), receivedAt, latencyMillis);
    }

    private String parser(IndexPriceProperties.SourceConfig source, boolean websocket) {
        if (websocket && source.getWebsocketParser() != null && !source.getWebsocketParser().isBlank()) {
            return source.getWebsocketParser();
        }
        return source.getParser();
    }

    private ParsedTicker parseTicker(String parser, JsonNode root) {
        return switch (parser.toUpperCase(Locale.ROOT)) {
            case "BINANCE_BOOK_TICKER" -> parseBinanceBookTicker(root);
            case "OKX_TICKER" -> parseOkxTicker(root);
            case "BYBIT_TICKER" -> parseBybitTicker(root);
            case "COINBASE_TICKER" -> parseCoinbaseTicker(root);
            case "KRAKEN_TICKER" -> parseKrakenTicker(root);
            default -> throw new IllegalArgumentException("Unsupported parser: " + parser);
        };
    }

    private ParsedTicker parseBinanceBookTicker(JsonNode root) {
        JsonNode ticker = root.has("data") ? root.path("data") : root;
        BigDecimal bid = firstDecimal(ticker, "bidPrice", "b");
        BigDecimal ask = firstDecimal(ticker, "askPrice", "a");
        Instant sourceTime = ticker.hasNonNull("E") ? Instant.ofEpochMilli(ticker.path("E").asLong()) : Instant.now();
        return new ParsedTicker(midOrLast(bid, ask, null), bid, ask, sourceTime,
                firstText(ticker, "symbol", "s"));
    }

    private ParsedTicker parseOkxTicker(JsonNode root) {
        JsonNode ticker = root.path("data").path(0);
        BigDecimal bid = decimal(ticker, "bidPx");
        BigDecimal ask = decimal(ticker, "askPx");
        BigDecimal last = decimal(ticker, "last");
        Instant sourceTime = epochMillis(ticker.path("ts").asText(null));
        return new ParsedTicker(midOrLast(bid, ask, last), bid, ask, sourceTime, firstText(ticker, "instId"));
    }

    private ParsedTicker parseBybitTicker(JsonNode root) {
        JsonNode ticker = root.path("data");
        if (ticker.isArray()) {
            ticker = ticker.path(0);
        }
        if (ticker.isMissingNode() || ticker.isNull() || ticker.isEmpty()) {
            ticker = root.path("result").path("list").path(0);
        }
        BigDecimal bid = decimal(ticker, "bid1Price");
        BigDecimal ask = decimal(ticker, "ask1Price");
        BigDecimal last = decimal(ticker, "lastPrice");
        return new ParsedTicker(midOrLast(bid, ask, last), bid, ask, Instant.now(), firstText(ticker, "symbol"));
    }

    private ParsedTicker parseCoinbaseTicker(JsonNode root) {
        BigDecimal bid = firstDecimal(root, "bid", "best_bid");
        BigDecimal ask = firstDecimal(root, "ask", "best_ask");
        BigDecimal price = decimal(root, "price");
        Instant sourceTime = root.hasNonNull("time") ? Instant.parse(root.get("time").asText()) : Instant.now();
        return new ParsedTicker(midOrLast(bid, ask, price), bid, ask, sourceTime, firstText(root, "product_id"));
    }

    private ParsedTicker parseKrakenTicker(JsonNode root) {
        JsonNode data = root.path("data");
        if (data.isArray() && !data.isEmpty()) {
            JsonNode ticker = data.path(0);
            BigDecimal bid = decimal(ticker, "bid");
            BigDecimal ask = decimal(ticker, "ask");
            BigDecimal last = decimal(ticker, "last");
            return new ParsedTicker(midOrLast(bid, ask, last), bid, ask, Instant.now(), firstText(ticker, "symbol"));
        }

        JsonNode result = root.path("result");
        if (result.propertyNames().isEmpty()) {
            throw new IllegalArgumentException("Kraken response has no ticker result");
        }
        String instrument = result.propertyNames().iterator().next();
        JsonNode ticker = result.path(instrument);
        BigDecimal bid = decimal(ticker.path("b"), 0);
        BigDecimal ask = decimal(ticker.path("a"), 0);
        BigDecimal last = decimal(ticker.path("c"), 0);
        return new ParsedTicker(midOrLast(bid, ask, last), bid, ask, Instant.now(), instrument);
    }

    private ConvertedTicker convertToTargetQuote(IndexPriceProperties.SourceConfig source, ParsedTicker ticker) {
        String quote = normalizedCurrency(source.getQuoteCurrency());
        String targetQuote = normalizedCurrency(source.getTargetQuoteCurrency());
        if (quote.equals(targetQuote)) {
            return new ConvertedTicker(ticker.price(), ticker.bidPrice(), ticker.askPrice(), BigDecimal.ONE, null);
        }
        if (!"USD".equals(quote) || !"USDT".equals(targetQuote)) {
            throw new IllegalArgumentException("Unsupported quote conversion: " + quote + " -> " + targetQuote);
        }

        try {
            BigDecimal rate = fetchConversionRate(source);
            return new ConvertedTicker(
                    applyConversion(ticker.price(), rate, source),
                    applyConversion(ticker.bidPrice(), rate, source),
                    applyConversion(ticker.askPrice(), rate, source),
                    BigDecimal.ONE,
                    "converted " + quote + " to " + targetQuote + " using rate=" + rate
                            + " operation=" + conversionOperation(source));
        } catch (Exception ex) {
            String mode = source.getConversionMode() == null ? "DISCOUNT" : source.getConversionMode().toUpperCase(Locale.ROOT);
            if ("DISABLE".equals(mode)) {
                throw new IllegalArgumentException("conversion failed and source disabled: " + ex.getMessage(), ex);
            }
            BigDecimal multiplier = source.getFallbackWeightMultiplier() == null
                    ? BigDecimal.ZERO
                    : source.getFallbackWeightMultiplier();
            return new ConvertedTicker(ticker.price(), ticker.bidPrice(), ticker.askPrice(), multiplier,
                    "conversion failed; used raw " + quote + " price with weight multiplier=" + multiplier);
        }
    }

    private BigDecimal fetchConversionRate(IndexPriceProperties.SourceConfig source) throws Exception {
        if (source.getConversionBaseUrl() == null || source.getConversionPath() == null || source.getConversionParser() == null) {
            throw new IllegalArgumentException("conversion endpoint is not configured");
        }

        String cacheKey = source.getConversionBaseUrl() + source.getConversionPath() + source.getConversionParser();
        CachedConversionRate cached = conversionRateCache.get(cacheKey);
        Instant now = Instant.now();
        if (cached != null && Duration.between(cached.updatedAt(), now)
                .compareTo(properties.getCalculation().getConversionCacheTtl()) <= 0) {
            return cached.rate();
        }

        BigDecimal rate = fetchTickerPrice(source.getConversionBaseUrl(), source.getConversionPath(), source.getConversionParser());
        conversionRateCache.put(cacheKey, new CachedConversionRate(rate, now));
        return rate;
    }

    private BigDecimal applyConversion(BigDecimal value, BigDecimal rate, IndexPriceProperties.SourceConfig source) {
        if (value == null) {
            return null;
        }
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("conversion rate must be positive");
        }
        String operation = conversionOperation(source);
        if ("DIVIDE".equals(operation)) {
            return value.divide(rate, properties.getCalculation().getScale(), RoundingMode.HALF_UP);
        }
        if ("MULTIPLY".equals(operation)) {
            return value.multiply(rate).setScale(properties.getCalculation().getScale(), RoundingMode.HALF_UP);
        }
        throw new IllegalArgumentException("Unsupported conversion operation: " + operation);
    }

    private String conversionOperation(IndexPriceProperties.SourceConfig source) {
        return source.getConversionOperation() == null || source.getConversionOperation().isBlank()
                ? "MULTIPLY"
                : source.getConversionOperation().trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal midOrLast(BigDecimal bid, BigDecimal ask, BigDecimal last) {
        if (positive(bid) && positive(ask)) {
            return bid.add(ask).divide(BigDecimal.valueOf(2), properties.getCalculation().getScale(), RoundingMode.HALF_UP);
        }
        if (positive(last)) {
            return last;
        }
        throw new IllegalArgumentException("ticker has no valid bid/ask/last price");
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private BigDecimal firstDecimal(JsonNode node, String... fields) {
        for (String field : fields) {
            BigDecimal value = decimal(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        return decimalValue(node.path(field));
    }

    private BigDecimal decimal(JsonNode node, int index) {
        return decimalValue(node.path(index));
    }

    private BigDecimal decimalValue(JsonNode value) {
        if (value.isArray()) {
            value = value.path(0);
        }
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return new BigDecimal(value.asText());
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private Instant epochMillis(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        return Instant.ofEpochMilli(Long.parseLong(value));
    }

    private boolean sameInstrument(String left, String right) {
        return normalizeInstrument(left).equals(normalizeInstrument(right));
    }

    private String normalizeInstrument(String instrument) {
        if (instrument == null) {
            return "";
        }
        String normalized = instrument.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (normalized.startsWith("XBT")) {
            return "BTC" + normalized.substring(3);
        }
        return normalized;
    }

    private SourceQuote error(IndexPriceProperties.SourceConfig source, SourceStatus status, String reason,
                              Instant receivedAt, Long latencyMillis) {
        return new SourceQuote(source.getName(), source.getSourceSymbol(), null, null, null, source.getWeight(),
                status, reason, null, receivedAt, latencyMillis);
    }

    private String normalizedCurrency(String currency) {
        return currency == null || currency.isBlank() ? "USDT" : currency.trim().toUpperCase(Locale.ROOT);
    }

    private Long elapsedMillis(Instant start, Instant end) {
        return Duration.between(start, end).toMillis();
    }

    private record ParsedTicker(BigDecimal price, BigDecimal bidPrice, BigDecimal askPrice,
                                Instant sourceTime, String instrument) {
    }

    private record ConvertedTicker(BigDecimal price, BigDecimal bidPrice, BigDecimal askPrice,
                                   BigDecimal weightMultiplier, String reason) {
    }

    private record CachedConversionRate(BigDecimal rate, Instant updatedAt) {
    }

    private static class IgnoredPayloadException extends RuntimeException {
    }
}
