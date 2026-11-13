package com.surprising.price.settings.model;

import java.math.BigDecimal;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 完整价格业务规则；构造时验证领域不变量，因此数据库损坏也不能安装到运行状态。 */
public record PriceBusinessSettings(
        long indexPollDelayMs,
        long indexMaxSourceAgeMs,
        BigDecimal indexOutlierThreshold,
        int indexMinValidSources,
        int indexScale,
        long conversionCacheTtlMs,
        long markPublishIntervalMs,
        long markBasisWindowMs,
        long markMaxInputAgeMs,
        BigDecimal markClampRatio,
        int defaultFundingIntervalHours,
        int markScale,
        boolean websocketEnabled,
        boolean restFallbackEnabled,
        long connectionRefreshDelayMs,
        boolean indexCoordinationEnabled,
        long indexLeaseDurationMs,
        boolean markCoordinationEnabled,
        long markLeaseDurationMs,
        boolean fiatEnabled,
        long fiatRefreshDelayMs,
        long fiatStaleAfterMs,
        String fiatBaseUrl,
        String fiatPath,
        String fiatBaseCurrency,
        List<String> fiatQuoteCurrencies,
        boolean stableEnabled,
        long stableRefreshDelayMs,
        long stableStaleAfterMs,
        String stableCurrency,
        String stableFiatCurrency,
        String stableBaseUrl,
        String stablePath,
        String stableParser,
        BigDecimal stableFallbackRate) {
    public PriceBusinessSettings {
        range("indexPollDelayMs", indexPollDelayMs, 50, 1000);
        range("indexMaxSourceAgeMs", indexMaxSourceAgeMs, 100, 600000);
        decimal("indexOutlierThreshold", indexOutlierThreshold, 1);
        range("indexMinValidSources", indexMinValidSources, 1, 20);
        range("indexScale", indexScale, 0, 18);
        range("conversionCacheTtlMs", conversionCacheTtlMs, 100, 3600000);
        range("markPublishIntervalMs", markPublishIntervalMs, 100, 1000);
        range("markBasisWindowMs", markBasisWindowMs, 1000, 3600000);
        range("markMaxInputAgeMs", markMaxInputAgeMs, 100, 600000);
        decimal("markClampRatio", markClampRatio, 1);
        range("defaultFundingIntervalHours", defaultFundingIntervalHours, 1, 168);
        range("markScale", markScale, 0, 18);
        range("connectionRefreshDelayMs", connectionRefreshDelayMs, 100, 60000);
        range("indexLeaseDurationMs", indexLeaseDurationMs, 1000, 600000);
        range("markLeaseDurationMs", markLeaseDurationMs, 1000, 600000);
        range("fiatRefreshDelayMs", fiatRefreshDelayMs, 1000, 86400000);
        range("fiatStaleAfterMs", fiatStaleAfterMs, 1000, 604800000);
        text("fiatBaseUrl", fiatBaseUrl, 0, 2048);
        text("fiatPath", fiatPath, 0, 1024);
        text("fiatBaseCurrency", fiatBaseCurrency, 1, 16);
        range("stableRefreshDelayMs", stableRefreshDelayMs, 1000, 86400000);
        range("stableStaleAfterMs", stableStaleAfterMs, 1000, 604800000);
        text("stableCurrency", stableCurrency, 1, 16);
        text("stableFiatCurrency", stableFiatCurrency, 1, 16);
        text("stableBaseUrl", stableBaseUrl, 0, 2048);
        text("stablePath", stablePath, 0, 1024);
        text("stableParser", stableParser, 1, 64);
        decimal("stableFallbackRate", stableFallbackRate, 1000000);
        if (fiatQuoteCurrencies == null || fiatQuoteCurrencies.size() > 100)
            throw new IllegalArgumentException("fiatQuoteCurrencies must contain at most 100 currency codes");
        fiatQuoteCurrencies = List.copyOf(fiatQuoteCurrencies);
        currency(fiatBaseCurrency); currency(stableCurrency); currency(stableFiatCurrency);
        var seen = new HashSet<String>();
        for (var value : fiatQuoteCurrencies) {
            currency(value);
            if (!seen.add(value)) throw new IllegalArgumentException("duplicate fiat currency");
        }
        if (fiatEnabled) {
            endpoint(fiatBaseUrl, fiatPath, "fiat");
            if (seen.isEmpty()) throw new IllegalArgumentException("fiatQuoteCurrencies required when enabled");
        }
        if (stableEnabled) {
            if (!fiatEnabled) throw new IllegalArgumentException("stable feed requires fiat conversion enabled");
            endpoint(stableBaseUrl, stablePath, "stable");
        }
        if (!Set.of("COINBASE_TICKER", "BINANCE_BOOK_TICKER", "OKX_TICKER", "BYBIT_TICKER").contains(stableParser))
            throw new IllegalArgumentException("unsupported stableParser");
    }

    public static PriceBusinessSettings initial() {
        return new PriceBusinessSettings(
                1000L,
                5000L,
                new BigDecimal("0.01"),
                3,
                18,
                30000L,
                1000L,
                60000L,
                15000L,
                new BigDecimal("0.03"),
                8,
                18,
                true,
                false,
                1000L,
                true,
                15000L,
                true,
                15000L,
                false,
                3600000L,
                108000000L,
                "",
                "",
                "USD",
                List.of(),
                false,
                10000L,
                300000L,
                "USDT",
                "USD",
                "",
                "",
                "COINBASE_TICKER",
                new BigDecimal("0"));
    }

    private static void range(String name, long value, long min, long max) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " outside [" + min + "," + max + "]");
    }
    private static void decimal(String name, BigDecimal value, long max) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0)
            throw new IllegalArgumentException(name + " outside [0," + max + "]");
    }
    private static void text(String name, String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max)
            throw new IllegalArgumentException(name + " length outside [" + min + "," + max + "]");
    }
    private static void currency(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9]{1,15}"))
            throw new IllegalArgumentException("invalid currency: " + value);
    }
    private static void endpoint(String baseUrl, String path, String prefix) {
        var uri = URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !path.startsWith("/"))
            throw new IllegalArgumentException("invalid " + prefix + " feed endpoint");
    }
}
