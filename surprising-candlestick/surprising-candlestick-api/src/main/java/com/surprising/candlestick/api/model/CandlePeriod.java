package com.surprising.candlestick.api.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;

public enum CandlePeriod {
    M1("1m", Duration.ofMinutes(1)),
    M3("3m", Duration.ofMinutes(3)),
    M5("5m", Duration.ofMinutes(5)),
    M15("15m", Duration.ofMinutes(15)),
    M30("30m", Duration.ofMinutes(30)),
    H1("1h", Duration.ofHours(1)),
    H2("2h", Duration.ofHours(2)),
    H4("4h", Duration.ofHours(4)),
    H6("6h", Duration.ofHours(6)),
    H12("12h", Duration.ofHours(12)),
    D1("1d", Duration.ofDays(1)),
    W1("1w", Duration.ofDays(7));

    private final String code;
    private final Duration duration;

    CandlePeriod(String code, Duration duration) {
        this.code = code;
        this.duration = duration;
    }

    public String code() {
        return code;
    }

    public Duration duration() {
        return duration;
    }

    public Instant floor(Instant instant) {
        long bucketMillis = duration.toMillis();
        long epochMillis = instant.toEpochMilli();
        return Instant.ofEpochMilli(Math.floorDiv(epochMillis, bucketMillis) * bucketMillis);
    }

    public Instant closeTime(Instant openTime) {
        return openTime.plus(duration);
    }

    public static CandlePeriod fromCode(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(period -> period.code.equals(normalized) || period.name().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported candle period: " + code));
    }
}
