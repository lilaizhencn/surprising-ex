package com.surprising.price.index.service;

import com.surprising.price.api.model.ExchangeRateConvertResponse;
import com.surprising.price.api.model.ExchangeRateQueryResponse;
import com.surprising.price.api.model.ExchangeRateResponse;
import com.surprising.price.index.client.ExternalSpotPriceClient;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.repository.ExchangeRateRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    private final IndexPriceProperties properties;
    private final ExchangeRateRepository exchangeRateRepository;
    private final ExchangeRateSnapshotCache snapshotCache;
    private final ExternalSpotPriceClient externalSpotPriceClient;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ExchangeRateService(IndexPriceProperties properties,
                               ExchangeRateRepository exchangeRateRepository,
                               ExternalSpotPriceClient externalSpotPriceClient,
                               ObjectMapper objectMapper) {
        this(properties, exchangeRateRepository, externalSpotPriceClient, objectMapper,
                new ExchangeRateSnapshotCache());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ExchangeRateService(IndexPriceProperties properties,
                               ExchangeRateRepository exchangeRateRepository,
                               ExternalSpotPriceClient externalSpotPriceClient,
                               ObjectMapper objectMapper,
                               ExchangeRateSnapshotCache snapshotCache) {
        this.properties = properties;
        this.exchangeRateRepository = exchangeRateRepository;
        this.snapshotCache = snapshotCache;
        this.externalSpotPriceClient = externalSpotPriceClient;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getHttp().getConnectTimeout())
                .build();
    }

    /** 启动时一次性恢复当前汇率，恢复完成前所有换算请求均失败关闭。 */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreSnapshot() {
        try {
            snapshotCache.restore(exchangeRateRepository.snapshot());
        } catch (RuntimeException ex) {
            snapshotCache.markNotReady();
            log.error("汇率 JVM 快照恢复失败", ex);
        }
    }

    public void refreshFiatRates() {
        IndexPriceProperties.Fiat fiat = properties.getFiat();
        if (!fiat.isEnabled()) {
            return;
        }
        String baseCurrency = normalizeCurrency(fiat.getBaseCurrency());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(fiat.getBaseUrl() + fiat.getPath()
                            .replace("{base}", baseCurrency)))
                    .timeout(properties.getHttp().getRequestTimeout())
                    .header("User-Agent", properties.getHttp().getUserAgent())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("fiat rate http status " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            saveOpenExchangeRateApiRates(baseCurrency, fiat, root);
        } catch (Exception ex) {
            log.warn("Failed to refresh fiat exchange rates provider={} base={}: {}",
                    fiat.getProvider(), baseCurrency, ex.getMessage());
        }
    }

    public void refreshStableCoinRate() {
        IndexPriceProperties.StableCoin stableCoin = properties.getFiat().getStableCoin();
        if (!properties.getFiat().isEnabled() || !stableCoin.isEnabled()) {
            return;
        }
        String stableCurrency = normalizeCurrency(stableCoin.getCurrency());
        String fiatCurrency = normalizeCurrency(stableCoin.getFiatCurrency());
        try {
            BigDecimal rate = externalSpotPriceClient.fetchTickerPrice(
                    stableCoin.getBaseUrl(), stableCoin.getPath(), stableCoin.getParser());
            saveRateAndInverse(stableCurrency, fiatCurrency, rate, "STABLE_COIN:" + stableCoin.getParser(),
                    Instant.now(), Instant.now());
        } catch (Exception ex) {
            log.warn("Failed to refresh stable coin rate {}->{}: {}", stableCurrency, fiatCurrency, ex.getMessage());
            if (snapshotCache.latest(stableCurrency, fiatCurrency).isEmpty()
                    && stableCoin.getFallbackRate() != null
                    && stableCoin.getFallbackRate().signum() > 0) {
                saveRateAndInverse(stableCurrency, fiatCurrency, stableCoin.getFallbackRate(), "STABLE_FALLBACK",
                        Instant.now(), Instant.now());
            }
        }
    }

    public ExchangeRateResponse latest(String baseCurrency, String quoteCurrency) {
        return resolveDirect(normalizeCurrency(baseCurrency), normalizeCurrency(quoteCurrency)).toResponse();
    }

    public ExchangeRateQueryResponse rates(String baseCurrency) {
        String normalized = normalizeCurrency(baseCurrency);
        List<ExchangeRateResponse> rates = snapshotCache.byBaseCurrency(normalized).stream()
                .filter(this::freshEnough)
                .toList();
        return new ExchangeRateQueryResponse(normalized, rates.size(), rates);
    }

    public ExchangeRateConvertResponse convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        String from = normalizeCurrency(fromCurrency);
        String to = normalizeCurrency(toCurrency);
        if (from.equals(to)) {
            return new ExchangeRateConvertResponse(from, to, amount, amount, BigDecimal.ONE,
                    from + "->" + to, Instant.now());
        }

        ResolvedRate direct = tryResolveDirect(from, to);
        if (direct != null) {
            return convertWithRate(amount, direct.rate(), from, to, direct.route(), direct.rateTime());
        }

        String bridge = normalizeCurrency(properties.getFiat().getBaseCurrency());
        ResolvedRate first = resolveDirect(from, bridge);
        ResolvedRate second = resolveDirect(bridge, to);
        BigDecimal rate = first.rate().multiply(second.rate())
                .setScale(properties.getCalculation().getScale(), RoundingMode.HALF_UP);
        Instant rateTime = first.rateTime().isBefore(second.rateTime()) ? first.rateTime() : second.rateTime();
        return convertWithRate(amount, rate, from, to, first.route() + "->" + to, rateTime);
    }

    private void saveOpenExchangeRateApiRates(String baseCurrency, IndexPriceProperties.Fiat fiat, JsonNode root) {
        if (!"success".equalsIgnoreCase(root.path("result").asString())) {
            throw new IllegalStateException("fiat provider result=" + root.path("result").asString());
        }
        String provider = root.path("provider").asString(fiat.getProvider());
        Instant rateTime = root.hasNonNull("time_last_update_unix")
                ? Instant.ofEpochSecond(root.path("time_last_update_unix").asLong())
                : Instant.now();
        Instant updatedAt = Instant.now();
        saveRateAndInverse(baseCurrency, baseCurrency, BigDecimal.ONE, provider, rateTime, updatedAt);
        for (String quote : fiat.getQuoteCurrencies()) {
            String quoteCurrency = normalizeCurrency(quote);
            JsonNode value = root.path("rates").path(quoteCurrency);
            if (!value.isMissingNode() && !value.isNull() && !value.asString().isBlank()) {
                BigDecimal rate = new BigDecimal(value.asString());
                if (rate.signum() > 0) {
                    saveRateAndInverse(baseCurrency, quoteCurrency, rate, provider, rateTime, updatedAt);
                }
            }
        }
    }

    private void saveRateAndInverse(String baseCurrency, String quoteCurrency, BigDecimal rate,
                                    String provider, Instant rateTime, Instant updatedAt) {
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("exchange rate must be positive");
        }
        BigDecimal normalizedRate = rate.setScale(properties.getCalculation().getScale(), RoundingMode.HALF_UP);
        exchangeRateRepository.upsert(baseCurrency, quoteCurrency, normalizedRate,
                provider, rateTime, updatedAt);
        snapshotCache.put(new ExchangeRateResponse(baseCurrency, quoteCurrency, normalizedRate,
                provider, rateTime, updatedAt));
        if (!baseCurrency.equals(quoteCurrency)) {
            BigDecimal inverse = BigDecimal.ONE.divide(normalizedRate, properties.getCalculation().getScale(),
                    RoundingMode.HALF_UP);
            exchangeRateRepository.upsert(quoteCurrency, baseCurrency, inverse,
                    provider + ":INVERSE", rateTime, updatedAt);
            snapshotCache.put(new ExchangeRateResponse(quoteCurrency, baseCurrency, inverse,
                    provider + ":INVERSE", rateTime, updatedAt));
        }
    }

    private ExchangeRateConvertResponse convertWithRate(BigDecimal amount, BigDecimal rate, String from,
                                                        String to, String route, Instant rateTime) {
        BigDecimal convertedAmount = amount.multiply(rate)
                .setScale(properties.getCalculation().getScale(), RoundingMode.HALF_UP);
        return new ExchangeRateConvertResponse(from, to, amount, convertedAmount, rate, route, rateTime);
    }

    private ResolvedRate resolveDirect(String baseCurrency, String quoteCurrency) {
        ResolvedRate rate = tryResolveDirect(baseCurrency, quoteCurrency);
        if (rate == null) {
            throw new IllegalStateException("exchange rate not found: " + baseCurrency + "->" + quoteCurrency);
        }
        return rate;
    }

    private ResolvedRate tryResolveDirect(String baseCurrency, String quoteCurrency) {
        if (baseCurrency.equals(quoteCurrency)) {
            return new ResolvedRate(BigDecimal.ONE, baseCurrency + "->" + quoteCurrency, "INTERNAL",
                    Instant.now(), Instant.now());
        }
        return snapshotCache.latest(baseCurrency, quoteCurrency)
                .filter(this::freshEnough)
                .map(rate -> new ResolvedRate(rate.rate(), rate.baseCurrency() + "->" + rate.quoteCurrency(),
                        rate.provider(), rate.rateTime(), rate.updatedAt()))
                .orElse(null);
    }

    private boolean freshEnough(ExchangeRateResponse rate) {
        Duration staleAfter = involvesStableCoin(rate.baseCurrency(), rate.quoteCurrency())
                ? properties.getFiat().getStableCoin().getStaleAfter()
                : properties.getFiat().getStaleAfter();
        return Duration.between(rate.updatedAt(), Instant.now()).compareTo(staleAfter) <= 0;
    }

    private boolean involvesStableCoin(String baseCurrency, String quoteCurrency) {
        String stable = normalizeCurrency(properties.getFiat().getStableCoin().getCurrency());
        return stable.equals(baseCurrency) || stable.equals(quoteCurrency);
    }

    private String normalizeCurrency(String currency) {
        if (currency == null || !currency.matches("[A-Za-z]{2,10}")) {
            throw new IllegalArgumentException("invalid currency: " + currency);
        }
        return currency.toUpperCase(Locale.ROOT);
    }

    private record ResolvedRate(BigDecimal rate, String route, String provider, Instant rateTime, Instant updatedAt) {

        private ExchangeRateResponse toResponse() {
            String[] currencies = route.split("->", 2);
            return new ExchangeRateResponse(currencies[0], currencies[1], rate, provider, rateTime, updatedAt);
        }
    }
}
