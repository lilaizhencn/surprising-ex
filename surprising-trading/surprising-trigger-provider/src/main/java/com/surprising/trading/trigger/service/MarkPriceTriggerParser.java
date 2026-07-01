package com.surprising.trading.trigger.service;

import com.surprising.trading.trigger.model.MarkTrigger;
import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses only the mark-price fields needed for trigger detection.
 *
 * <p>The price module's display DTO can evolve independently; trigger execution reads the canonical
 * persisted mark units from PostgreSQL by symbol and sequence before comparing ticks.</p>
 */
@Component
public class MarkPriceTriggerParser {

    private final ObjectMapper objectMapper;

    public MarkPriceTriggerParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MarkTrigger parse(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String symbol = text(root, "symbol");
            long sequence = root.path("sequence").asLong();
            if (sequence <= 0) {
                throw new IllegalArgumentException("mark sequence must be positive");
            }
            String eventTime = text(root, "eventTime");
            return new MarkTrigger(normalizeSymbol(symbol), sequence, Instant.parse(eventTime));
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid mark price payload", ex);
        }
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
