package com.surprising.gateway.provider.option;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.option.OptionQuoteModels.OptionQuoteResponse;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OptionQuoteService {

    private final GatewayProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OptionQuoteService(GatewayProperties properties,
                              RestTemplate restTemplate,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public OptionQuoteResponse quote(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        JsonNode instrument = getJson("instrument", "/latest", Map.of("symbol", normalizedSymbol), ProductLine.OPTION);
        String underlyingSymbol = requiredText(instrument, "underlyingSymbol");
        String optionType = requiredText(instrument, "optionType").toUpperCase(Locale.ROOT);
        Instant expiryTime = requiredInstant(instrument, "expiryTime");
        if (!expiryTime.isAfter(Instant.now())) {
            throw new OptionQuoteUnavailableException("option has expired");
        }
        long priceTickUnits = requiredLong(instrument, "priceTickUnits");
        long strikePriceUnits = requiredLong(instrument, "strikePriceUnits");
        BigDecimal scale = BigDecimal.valueOf(priceTickUnits);
        BigDecimal strike = BigDecimal.valueOf(strikePriceUnits).divide(scale, 18, java.math.RoundingMode.HALF_UP);
        JsonNode index = getJson("price-index", "/latest", Map.of("symbol", underlyingSymbol), ProductLine.OPTION);
        BigDecimal underlyingPrice = requiredDecimal(index, "indexPrice");
        JsonNode latestTrade = getJson("trading-market", "/latest-trade",
                Map.of("symbol", normalizedSymbol), ProductLine.OPTION);
        BigDecimal optionPrice = BigDecimal.valueOf(requiredLong(latestTrade, "priceTicks"))
                .divide(scale, 18, java.math.RoundingMode.HALF_UP);
        Instant asOf = optionalInstant(latestTrade, "eventTime").orElse(Instant.now());
        double timeYears = Duration.between(Instant.now(), expiryTime).toNanos() / 1_000_000_000.0d
                / (365.25d * 24.0d * 60.0d * 60.0d);
        boolean call = "CALL".equals(optionType);
        if (!call && !"PUT".equals(optionType)) {
            throw new OptionQuoteUnavailableException("unsupported option type: " + optionType);
        }
        BlackScholesCalculator.Result greeks;
        try {
            greeks = BlackScholesCalculator.solve(underlyingPrice.doubleValue(), strike.doubleValue(),
                    optionPrice.doubleValue(), timeYears, call);
        } catch (IllegalArgumentException ex) {
            throw new OptionQuoteUnavailableException(ex.getMessage());
        }
        return new OptionQuoteResponse(
                normalizedSymbol, underlyingSymbol, optionType, expiryTime, asOf,
                underlyingPrice, optionPrice, strike, BlackScholesCalculator.decimal(timeYears),
                BlackScholesCalculator.decimal(greeks.volatility()), BlackScholesCalculator.decimal(greeks.delta()),
                BlackScholesCalculator.decimal(greeks.gamma()), BlackScholesCalculator.decimal(greeks.theta()),
                BlackScholesCalculator.decimal(greeks.vega()), BlackScholesCalculator.decimal(greeks.rho()));
    }

    private JsonNode getJson(String routeName, String suffix, Map<String, String> query, ProductLine productLine) {
        GatewayProperties.BackendRoute configured = properties.getRoutes().get(routeName);
        GatewayProperties.BackendRoute route = configured == null ? null : configured.resolve(productLine);
        if (route == null || route.getBaseUrl() == null || route.getTargetPrefix() == null) {
            throw new OptionQuoteUnavailableException("option quote route is not configured: " + routeName);
        }
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(trimTrailingSlash(route.getBaseUrl()) + leadingSlash(route.getTargetPrefix()) + suffix);
        query.forEach(builder::queryParam);
        URI target = builder.build().encode().toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Product-Line", productLine.name());
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(target, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
        } catch (RestClientException ex) {
            throw new OptionQuoteUnavailableException("fresh option market data is unavailable");
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new OptionQuoteUnavailableException("fresh option market data is unavailable");
        }
        try {
            return objectMapper.readTree(response.getBody());
        } catch (JacksonException ex) {
            throw new OptionQuoteUnavailableException("option market data response is invalid");
        }
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[A-Z0-9][A-Z0-9_/-]{1,63}")) {
            throw new IllegalArgumentException("invalid option symbol");
        }
        return symbol;
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asString("").trim();
        if (value.isBlank()) throw new OptionQuoteUnavailableException("missing option field: " + field);
        return value;
    }

    private long requiredLong(JsonNode node, String field) {
        JsonNode raw = node.get(field);
        if (raw == null || raw.isNull()) {
            throw new OptionQuoteUnavailableException("missing option field: " + field);
        }
        try {
            return raw.isNumber() ? raw.longValue() : Long.parseLong(raw.asString("").trim());
        } catch (NumberFormatException ex) {
            throw new OptionQuoteUnavailableException("invalid option field: " + field);
        }
    }

    private BigDecimal requiredDecimal(JsonNode node, String field) {
        JsonNode raw = node.get(field);
        if (raw == null || raw.isNull()) {
            throw new OptionQuoteUnavailableException("missing option field: " + field);
        }
        try {
            return new BigDecimal(raw.isNumber() ? raw.toString() : raw.asString("").trim());
        } catch (NumberFormatException ex) {
            throw new OptionQuoteUnavailableException("invalid option field: " + field);
        }
    }

    private Instant requiredInstant(JsonNode node, String field) {
        return optionalInstant(node, field)
                .orElseThrow(() -> new OptionQuoteUnavailableException("missing option field: " + field));
    }

    private java.util.Optional<Instant> optionalInstant(JsonNode node, String field) {
        String value = node.path(field).asString("").trim();
        if (value.isBlank()) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(Instant.parse(value));
        } catch (java.time.format.DateTimeParseException ex) {
            throw new OptionQuoteUnavailableException("invalid option field: " + field);
        }
    }

    private String trimTrailingSlash(String value) {
        return value.replaceFirst("/$", "");
    }

    private String leadingSlash(String value) {
        return value.startsWith("/") ? value : "/" + value;
    }

    public static class OptionQuoteUnavailableException extends RuntimeException {
        public OptionQuoteUnavailableException(String message) {
            super(message == null || message.isBlank() ? "option quote is unavailable" : message);
        }
    }
}
