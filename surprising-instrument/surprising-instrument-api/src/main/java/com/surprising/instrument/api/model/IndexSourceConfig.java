package com.surprising.instrument.api.model;

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
        long fallbackWeightMultiplierPpm,
        boolean websocketEnabled,
        String websocketUrl,
        String websocketSubscribeMessage,
        String websocketParser,
        long weightPpm) {
}
