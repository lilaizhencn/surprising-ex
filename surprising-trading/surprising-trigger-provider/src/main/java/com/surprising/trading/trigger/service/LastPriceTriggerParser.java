package com.surprising.trading.trigger.service;

import com.surprising.trading.trigger.model.LastPriceTrigger;
import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses match-trade events for trigger orders using LAST_PRICE as their trigger source.
 */
@Component
public class LastPriceTriggerParser {

    private final ObjectMapper objectMapper;

    public LastPriceTriggerParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LastPriceTrigger parse(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String symbol = text(root, "symbol");
            long tradeId = root.path("tradeId").asLong();
            long priceTicks = root.path("priceTicks").asLong();
            if (tradeId <= 0) {
                throw new IllegalArgumentException("tradeId must be positive");
            }
            if (priceTicks <= 0) {
                throw new IllegalArgumentException("priceTicks must be positive");
            }
            return new LastPriceTrigger(normalizeSymbol(symbol), tradeId, priceTicks, parseEventTime(root));
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid match trade payload", ex);
        }
    }

    private Instant parseEventTime(JsonNode root) {
        String raw = text(root, "eventTime").trim();
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
            return parseEpochSeconds(raw);
        }
    }

    private Instant parseEpochSeconds(String raw) {
        ParsedNumber number = parseNumber(raw);
        if (number.scale() > 18 || number.scale() < -18) {
            throw new IllegalArgumentException("eventTime scale is out of range");
        }
        if (number.value().signum() <= 0) {
            throw new IllegalArgumentException("eventTime must be positive");
        }
        if (number.scale() <= 0) {
            BigInteger seconds = number.value().multiply(BigInteger.TEN.pow(-number.scale()));
            return Instant.ofEpochSecond(seconds.longValueExact());
        }

        BigInteger divisor = BigInteger.TEN.pow(number.scale());
        BigInteger[] parts = number.value().divideAndRemainder(divisor);
        BigInteger nanos = parts[1].multiply(BigInteger.valueOf(1_000_000_000L)).divide(divisor);
        return Instant.ofEpochSecond(parts[0].longValueExact(), nanos.intValueExact());
    }

    private ParsedNumber parseNumber(String raw) {
        String upper = raw.trim().toUpperCase();
        int exponentSeparator = upper.indexOf('E');
        String coefficient = exponentSeparator >= 0 ? upper.substring(0, exponentSeparator) : upper;
        int exponent = exponentSeparator >= 0 ? Integer.parseInt(upper.substring(exponentSeparator + 1)) : 0;
        int dot = coefficient.indexOf('.');
        String whole = dot >= 0 ? coefficient.substring(0, dot) : coefficient;
        String fraction = dot >= 0 ? coefficient.substring(dot + 1) : "";
        if (whole.startsWith("+")) {
            whole = whole.substring(1);
        }
        if (whole.isBlank()) {
            whole = "0";
        }
        String digits = whole + fraction;
        if (digits.isBlank() || !digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("eventTime numeric value is invalid");
        }
        return new ParsedNumber(new BigInteger(digits), fraction.length() - exponent);
    }

    private record ParsedNumber(BigInteger value, int scale) {
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText();
    }

    private String normalizeSymbol(String symbol) {
        String normalized = symbol.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return normalized;
    }
}
