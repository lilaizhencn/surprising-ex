package com.surprising.instrument.api.model;

import java.math.BigDecimal;

public record IndexSourceConfig(
        String source,
        boolean enabled,
        String baseUrl,
        String path,
        String sourceSymbol,
        String parser,
        String quoteCurrency,
        String targetQuoteCurrency,
        String conversionBaseUrl,
        String conversionPath,
        String conversionParser,
        String conversionMode,
        String conversionOperation,
        BigDecimal fallbackWeightMultiplier,
        boolean websocketEnabled,
        String websocketUrl,
        String websocketSubscribeMessage,
        String websocketParser,
        BigDecimal weight) {
}
