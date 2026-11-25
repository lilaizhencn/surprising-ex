package com.surprising.marketmaker.provider.service;

import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.utils.InstrumentIds;
import com.surprising.trading.api.model.MarginMode;
import java.util.Locale;
import java.util.UUID;

/**
 * 做市策略参数标准化、边界校验与键构造辅助。
 */
final class MarketMakerParameterSupport {

    private MarketMakerParameterSupport() {
    }

    static String normalizeRequired(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    static String normalizeSymbol(String value) {
        String normalized = normalizeRequired(value, "instrumentId");
        if (!InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("invalid instrumentId: " + value);
        }
        return normalized;
    }

    static String normalizeReason(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("reason must be at most 500 characters");
        }
        return normalized;
    }

    static MarginMode parseMarginMode(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return null;
        }
        try {
            return MarginMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid marginMode: " + value, ex);
        }
    }

    static Long positiveOrNull(Long value, String field) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    static Long nonNegativeOrNull(Long value, String field) {
        if (value == null) {
            return null;
        }
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    static Long boundedLongOrNull(Long value, long min, long max, String field) {
        if (value == null) {
            return null;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    static Integer boundedIntOrNull(Integer value, int min, int max, String field) {
        if (value == null) {
            return null;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    static String strategyKey(MarketMakerProperties.Strategy strategy) {
        return strategy == null ? strategyKey(null, null) : strategyKey(strategy.getProductLine(), strategy.getStrategyId());
    }

    static String strategyKey(ProductLine productLine, String strategyId) {
        ProductLine safeProductLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        String safeStrategyId = strategyId == null ? "" : strategyId.trim().toLowerCase(Locale.ROOT);
        return safeProductLine.name() + ":" + safeStrategyId;
    }

    static String resolveNodeId(String configuredNodeId) {
        if (configuredNodeId != null && !configuredNodeId.isBlank()) {
            return configuredNodeId.trim();
        }
        return "market-maker-" + UUID.randomUUID();
    }
}
