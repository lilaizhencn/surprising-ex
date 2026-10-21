package com.surprising.instrument.provider.utils;

import com.surprising.instrument.api.model.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 配置表示格式的纯校验。HTTP入口先检查；内部安装/持久化配置复用时不依赖Controller。 */
public final class InstrumentConfigurationFormats {
    private InstrumentConfigurationFormats() {}

    public static void validate(InstrumentUpsertRequest request) {
        if (request == null) throw new IllegalArgumentException("instrument configuration is required");
        requireSymbol(request.symbol());
        if (request.instrumentId() != null) requirePositive("instrumentId", request.instrumentId());
        requirePositive("baseAssetId", request.baseAssetId());
        requirePositive("quoteAssetId", request.quoteAssetId());
        requirePositive("settleAssetId", request.settleAssetId());
        requirePositive("contractValueAssetId", request.contractValueAssetId());
        if (request.status() == null) throw new IllegalArgumentException("status is required");
        requirePrecision("pricePrecision", request.pricePrecision());
        requirePrecision("quantityPrecision", request.quantityPrecision());
        requireChoices("supportedOrderTypes", request.supportedOrderTypes(), Set.of("LIMIT", "MARKET"));
        requireChoices("supportedTimeInForce", request.supportedTimeInForce(), Set.of("GTC", "IOC", "FOK", "GTX"));
        if (request.instrumentType() == null) throw new IllegalArgumentException("instrumentType is required");
        if (request.contractType() == null) throw new IllegalArgumentException("contractType is required");
        if (request.instrumentType() == InstrumentType.OPTION && request.underlyingInstrumentId() != null && !request.underlyingInstrumentId().isBlank())
            com.surprising.product.api.utils.InstrumentIds.parse(request.underlyingInstrumentId());
        if (request.indexSources() != null) {
            for (IndexSourceConfig source : request.indexSources()) {
                if (source == null) throw new IllegalArgumentException("index source must not be null");
                validateSource(source);
            }
        }
    }

    private static void validateSource(IndexSourceConfig source) {
        requireUrl("index source baseUrl", source.baseUrl(), Set.of("https", "http"));
        if (source.path() == null || !source.path().startsWith("/") || source.path().startsWith("//"))
            throw new IllegalArgumentException("index source path must start with a single slash");
        if (source.sourceSymbol() == null || source.sourceSymbol().isBlank()
                || source.parser() == null || source.parser().isBlank())
            throw new IllegalArgumentException("index source symbol and parser are required");
        if (source.quoteCurrency() == null || source.quoteCurrency().isBlank()
                || source.targetQuoteCurrency() == null || source.targetQuoteCurrency().isBlank())
            throw new IllegalArgumentException("index source quote currencies are required");
        requireParser(source.parser());
        if (!source.quoteCurrency().equalsIgnoreCase(source.targetQuoteCurrency())) {
            requireUrl("conversionBaseUrl", source.conversionBaseUrl(), Set.of("https", "http"));
            if (source.conversionPath() == null || !source.conversionPath().startsWith("/")
                    || source.conversionPath().startsWith("//"))
                throw new IllegalArgumentException("conversionPath must start with a single slash");
            requireParser(source.conversionParser());
        }
        if (source.websocketEnabled()) {
            requireParser(source.websocketParser());
            requireUrl("index source websocketUrl", source.websocketUrl(), Set.of("wss", "ws"));
            if (source.websocketParser() == null || source.websocketParser().isBlank()
                    || source.websocketSubscribeMessage() == null || source.websocketSubscribeMessage().isBlank())
                throw new IllegalArgumentException("WebSocket parser and subscription message are required");
            try { new tools.jackson.databind.ObjectMapper().readTree(source.websocketSubscribeMessage()); }
            catch (RuntimeException error) { throw new IllegalArgumentException("WebSocket subscription message must be valid JSON"); }
        }
        if (source.conversionMode() == null || !Set.of("DISCOUNT", "DISABLE").contains(source.conversionMode()))
            throw new IllegalArgumentException("conversionMode must be DISCOUNT or DISABLE");
        if (source.conversionOperation() == null || !Set.of("MULTIPLY", "DIVIDE").contains(source.conversionOperation()))
            throw new IllegalArgumentException("conversionOperation must be MULTIPLY or DIVIDE");
        if (source.source() == null || source.source().isBlank()) {
            throw new IllegalArgumentException("index source name is required");
        }
    }

    private static void requireParser(String parser) {
        if (parser == null || !Set.of("BINANCE_BOOK_TICKER", "OKX_TICKER", "OKX_INDEX_TICKER",
                "BYBIT_TICKER", "COINBASE_TICKER", "KRAKEN_TICKER", "OPTION_RISK_TICKER").contains(parser))
            throw new IllegalArgumentException("unsupported index source parser: " + parser);
    }

    private static void requirePrecision(String name, int value) {
        if (value < 0 || value > 18) throw new IllegalArgumentException(name + " must be in [0,18]");
    }

    private static void requireChoices(String name, List<String> values, Set<String> allowed) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(v -> v == null || !allowed.contains(v))
                || new HashSet<>(values).size() != values.size())
            throw new IllegalArgumentException(name + " must contain unique supported values: " + allowed);
    }

    private static void requireUrl(String name, String value, Set<String> schemes) {
        try {
            var uri = java.net.URI.create(value);
            if (!schemes.contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(name + " must be an absolute URL using " + schemes);
        }
    }

    private static void requireSymbol(String symbol) {
        if (symbol == null || symbol.endsWith("-SWAP") || !symbol.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
    }

    private static void requirePositive(String name, long value) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
