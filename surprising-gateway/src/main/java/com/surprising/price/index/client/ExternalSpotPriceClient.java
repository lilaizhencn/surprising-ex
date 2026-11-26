package com.surprising.price.index.client;

import com.surprising.price.api.model.QuoteTransport;
import com.surprising.price.api.model.SourceStatus;
import com.surprising.price.index.client.ExternalSpotTickerParser.ParsedTicker;
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
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ExternalSpotPriceClient {

    private final IndexPriceProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final Semaphore requestPermits;
    private final HttpClient httpClient;
    private final Map<String, CachedConversionRate> conversionRateCache = new ConcurrentHashMap<>();

    public ExternalSpotPriceClient(IndexPriceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.requestPermits = new Semaphore(Math.max(1, properties.getHttp().getMaxConcurrentRequests()));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getHttp().getConnectTimeout())
                .proxy(properties.getHttp().proxySelector())
                .build();
    }

    public CompletableFuture<SourceQuote> fetch(IndexPriceProperties.SourceConfig source) {
        if (!source.isEnabled()) {
            return CompletableFuture.completedFuture(error(source, SourceStatus.DISABLED, "source disabled", Instant.now(), null));
        }

        return CompletableFuture.supplyAsync(() -> fetchBlocking(source), executorService);
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
            return parseWebSocketPayload(source, parseWebSocketMessage(payload), receivedAt);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /** One socket message is parsed once before matching its configured sources. */
    JsonNode parseWebSocketMessage(String payload) {
        return objectMapper.readTree(payload);
    }

    Optional<SourceQuote> parseWebSocketPayload(IndexPriceProperties.SourceConfig source, JsonNode root,
                                                Instant receivedAt) {
        try {
            return Optional.ofNullable(toSourceQuote(source, root, receivedAt, null, true));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public BigDecimal fetchTickerPrice(String baseUrl, String path, String parser) throws Exception {
        HttpResponse<String> response = sendGet(baseUrl + path);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException("ticker http status " + response.statusCode());
        }
        ParsedTicker ticker = ExternalSpotTickerParser.parseTicker(
                parser, objectMapper.readTree(response.body()), properties.getCalculation().getScale());
        return ticker.price();
    }

    @PreDestroy
    public void close() {
        executorService.shutdownNow();
    }

    private SourceQuote fetchBlocking(IndexPriceProperties.SourceConfig source) {
        Instant start = Instant.now();
        try {
            HttpResponse<String> response = sendGet(source.getBaseUrl() + source.getPath());
            return parseResponse(source, response, start);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return error(source, SourceStatus.ERROR, "request interrupted", Instant.now(),
                    elapsedMillis(start, Instant.now()));
        } catch (Exception ex) {
            return error(source, SourceStatus.ERROR, ex.getMessage(), Instant.now(),
                    elapsedMillis(start, Instant.now()));
        }
    }

    private HttpResponse<String> sendGet(String url) throws Exception {
        boolean acquired = false;
        try {
            requestPermits.acquire();
            acquired = true;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(properties.getHttp().getRequestTimeout())
                    .header("User-Agent", properties.getHttp().getUserAgent())
                    .GET()
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } finally {
            if (acquired) {
                requestPermits.release();
            }
        }
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
        return toSourceQuote(source, objectMapper.readTree(payload), receivedAt, latencyMillis, websocket);
    }

    private SourceQuote toSourceQuote(IndexPriceProperties.SourceConfig source, JsonNode root, Instant receivedAt,
                                      Long latencyMillis, boolean websocket) throws Exception {
        String parser = parser(source, websocket);
        if (websocket && !ExternalSpotTickerParser.matchesWebSocketAncestry(source, parser, root)) return null;
        if ("OPTION_RISK_TICKER".equalsIgnoreCase(parser)) {
            if (!source.getSourceSymbol().equals(root.path("symbol").asString())
                    || !normalizedCurrency(source.getQuoteCurrency()).equals(normalizedCurrency(source.getTargetQuoteCurrency())))
                throw new IllegalArgumentException("option quote symbol and quote currency must match configuration");
            var reference = new com.surprising.price.api.model.OptionPriceReference(
                    new BigDecimal(root.path("markPrice").asString()),
                    new BigDecimal(root.path("sameExpiryForwardPrice").asString()),
                    Instant.parse(root.path("expiryTime").asString()));
            var index = new BigDecimal(root.path("indexPrice").asString());
            var timestamp = Instant.ofEpochMilli(root.path("timestamp").asLong());
            if (index.signum() <= 0 || timestamp.toEpochMilli() <= 0 || timestamp.isAfter(receivedAt)
                    || !reference.expiryTime().isAfter(receivedAt))
                throw new IllegalArgumentException("option quote requires positive index, non-future timestamp and unexpired expiry");
            return new SourceQuote(source.getName(), source.getSourceSymbol(), index, null, null,
                    source.getWeight(), SourceStatus.HEALTHY, null, timestamp, receivedAt, latencyMillis,
                    websocket ? QuoteTransport.PUBLIC_WEBSOCKET : QuoteTransport.REST, reference);
        }
        ParsedTicker ticker = ExternalSpotTickerParser.parseTicker(parser, root, properties.getCalculation().getScale());
        if (websocket && ticker.instrument() != null
                && !ExternalSpotTickerParser.sameInstrument(ticker.instrument(), source.getSourceSymbol())) {
            return null;
        }
        ConvertedTicker converted = convertToTargetQuote(source, ticker);
        BigDecimal configuredWeight = source.getWeight().multiply(converted.weightMultiplier());
        return new SourceQuote(source.getName(), source.getSourceSymbol(), converted.price(), converted.bidPrice(),
                converted.askPrice(), configuredWeight, SourceStatus.HEALTHY, converted.reason(),
                ticker.sourceTime(), receivedAt, latencyMillis,
                websocket ? QuoteTransport.PUBLIC_WEBSOCKET : QuoteTransport.REST);
    }

    private String parser(IndexPriceProperties.SourceConfig source, boolean websocket) {
        if (websocket && source.getWebsocketParser() != null && !source.getWebsocketParser().isBlank()) {
            return source.getWebsocketParser();
        }
        return source.getParser();
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

    private SourceQuote error(IndexPriceProperties.SourceConfig source, SourceStatus status, String reason,
                              Instant receivedAt, Long latencyMillis) {
        return new SourceQuote(source.getName(), source.getSourceSymbol(), null, null, null, source.getWeight(),
                status, reason, null, receivedAt, latencyMillis, QuoteTransport.REST);
    }

    private String normalizedCurrency(String currency) {
        return currency == null || currency.isBlank() ? "USDT" : currency.trim().toUpperCase(Locale.ROOT);
    }

    private Long elapsedMillis(Instant start, Instant end) {
        return Duration.between(start, end).toMillis();
    }

    private record ConvertedTicker(BigDecimal price, BigDecimal bidPrice, BigDecimal askPrice,
                                   BigDecimal weightMultiplier, String reason) {
    }

    private record CachedConversionRate(BigDecimal rate, Instant updatedAt) {
    }
}
