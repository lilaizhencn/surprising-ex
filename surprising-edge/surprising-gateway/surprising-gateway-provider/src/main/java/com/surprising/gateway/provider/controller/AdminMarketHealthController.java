package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.AuthService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/market")
public class AdminMarketHealthController {

    private final AuthService authService;
    private final JdbcTemplate jdbcTemplate;

    public AdminMarketHealthController(AuthService authService, JdbcTemplate jdbcTemplate) {
        this.authService = authService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    public MarketHealthResponse health(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                       @RequestParam(value = "symbol", required = false) String symbol,
                                       @RequestParam(value = "period", defaultValue = "1m") String period,
                                       @RequestParam(value = "staleSeconds", defaultValue = "120") long staleSeconds,
                                       @RequestParam(value = "limit", defaultValue = "50") int limit) {
        try {
            authService.requireAdminPermission(authorization, "admin.market.read");
            Instant now = Instant.now();
            String normalizedSymbol = normalizeSymbol(symbol);
            String normalizedPeriod = normalizePeriod(period);
            long boundedStaleSeconds = Math.max(10, Math.min(staleSeconds, 86_400));
            int boundedLimit = Math.max(1, Math.min(limit, 200));
            List<MarketHealthWarning> warnings = new ArrayList<>();
            List<MarketSymbolHealth> symbols = symbolHealth(now, normalizedSymbol, normalizedPeriod,
                    boundedStaleSeconds, boundedLimit, warnings);
            List<MarketSourceHealth> sources = sourceHealth(normalizedSymbol, boundedLimit, warnings);
            return new MarketHealthResponse(now, normalizedPeriod, boundedStaleSeconds,
                    summary(symbols), symbols, sources, warnings);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private List<MarketSymbolHealth> symbolHealth(Instant now,
                                                  String symbol,
                                                  String period,
                                                  long staleSeconds,
                                                  int limit,
                                                  List<MarketHealthWarning> warnings) {
        try {
            return jdbcTemplate.queryForList("""
                    WITH current_instruments AS (
                        SELECT i.symbol, i.status AS instrument_status, i.instrument_type, i.contract_type
                          FROM instrument_current_versions cv
                          JOIN instruments i ON i.symbol = cv.symbol AND i.version = cv.version
                         WHERE (CAST(? AS text) IS NULL OR i.symbol = ?)
                    ),
                    latest_index AS (
                        SELECT DISTINCT ON (symbol)
                               symbol, sequence, index_price, status AS index_status,
                               component_count, valid_component_count, event_time
                          FROM price_index_ticks
                         WHERE (CAST(? AS text) IS NULL OR symbol = ?)
                         ORDER BY symbol, event_time DESC, sequence DESC
                    ),
                    latest_mark AS (
                        SELECT DISTINCT ON (symbol)
                               symbol, sequence, mark_price, index_price AS mark_index_price,
                               mark_price_units, funding_rate, status AS mark_status, event_time
                          FROM price_mark_ticks
                         WHERE (CAST(? AS text) IS NULL OR symbol = ?)
                         ORDER BY symbol, event_time DESC, sequence DESC
                    ),
                    latest_candle AS (
                        SELECT DISTINCT ON (symbol)
                               symbol, period, status AS candle_status, close_time, updated_at, trade_count
                          FROM candlestick_candles
                         WHERE period = ?
                           AND (CAST(? AS text) IS NULL OR symbol = ?)
                         ORDER BY symbol, updated_at DESC, open_time DESC
                    ),
                    component_agg AS (
                        SELECT c.symbol, c.sequence,
                               COUNT(*) AS source_count,
                               COUNT(*) FILTER (WHERE c.status = 'HEALTHY') AS healthy_sources,
                               COUNT(*) FILTER (WHERE c.status <> 'HEALTHY') AS unhealthy_sources,
                               COALESCE(MAX(c.latency_millis), 0) AS max_source_latency_millis,
                               COALESCE(ROUND(AVG(c.latency_millis)), 0) AS avg_source_latency_millis,
                               string_agg(c.source || ':' || c.status, ',' ORDER BY c.source) AS source_statuses
                          FROM price_index_components c
                          JOIN latest_index li ON li.symbol = c.symbol AND li.sequence = c.sequence
                         GROUP BY c.symbol, c.sequence
                    )
                    SELECT ci.symbol, ci.instrument_status, ci.instrument_type, ci.contract_type,
                           li.index_price, li.index_status, li.component_count, li.valid_component_count,
                           li.event_time AS index_event_time,
                           lm.mark_price, lm.mark_index_price, lm.mark_price_units, lm.funding_rate,
                           lm.mark_status, lm.event_time AS mark_event_time,
                           lc.period AS candle_period, lc.candle_status, lc.close_time AS candle_close_time,
                           lc.updated_at AS candle_updated_at, lc.trade_count AS candle_trade_count,
                           COALESCE(ca.source_count, 0) AS source_count,
                           COALESCE(ca.healthy_sources, 0) AS healthy_sources,
                           COALESCE(ca.unhealthy_sources, 0) AS unhealthy_sources,
                           COALESCE(ca.max_source_latency_millis, 0) AS max_source_latency_millis,
                           COALESCE(ca.avg_source_latency_millis, 0) AS avg_source_latency_millis,
                           ca.source_statuses
                      FROM current_instruments ci
                      LEFT JOIN latest_index li ON li.symbol = ci.symbol
                      LEFT JOIN latest_mark lm ON lm.symbol = ci.symbol
                     LEFT JOIN latest_candle lc ON lc.symbol = ci.symbol
                     LEFT JOIN component_agg ca ON ca.symbol = ci.symbol
                     ORDER BY
                           CASE WHEN ci.instrument_status = 'TRADING' THEN 0 ELSE 1 END,
                           COALESCE(lm.event_time, li.event_time, lc.updated_at, TIMESTAMPTZ 'epoch') DESC,
                           ci.symbol
                     LIMIT ?
                    """, symbol, symbol, symbol, symbol, symbol, symbol, period, symbol, symbol, limit)
                    .stream()
                    .map(row -> toSymbolHealth(row, now, staleSeconds))
                    .toList();
        } catch (DataAccessException ex) {
            warnings.add(new MarketHealthWarning("symbols", "price_index_ticks/price_mark_ticks", ex.getMessage()));
            return List.of();
        }
    }

    private List<MarketSourceHealth> sourceHealth(String symbol, int limit, List<MarketHealthWarning> warnings) {
        try {
            return jdbcTemplate.queryForList("""
                    WITH latest_index AS (
                        SELECT DISTINCT ON (symbol)
                               symbol, sequence
                          FROM price_index_ticks
                         WHERE (CAST(? AS text) IS NULL OR symbol = ?)
                         ORDER BY symbol, event_time DESC, sequence DESC
                    )
                    SELECT c.symbol, c.source, c.source_symbol, c.status, c.reason,
                           c.price, c.bid_price, c.ask_price, c.configured_weight, c.effective_weight,
                           c.source_time, c.received_at, c.latency_millis
                      FROM price_index_components c
                      JOIN latest_index li ON li.symbol = c.symbol AND li.sequence = c.sequence
                     ORDER BY c.symbol, CASE WHEN c.status = 'HEALTHY' THEN 1 ELSE 0 END,
                              c.latency_millis DESC NULLS LAST, c.source
                     LIMIT ?
                    """, symbol, symbol, Math.max(1, limit * 10))
                    .stream()
                    .map(row -> new MarketSourceHealth(
                            stringValue(row.get("symbol")),
                            stringValue(row.get("source")),
                            stringValue(row.get("source_symbol")),
                            stringValue(row.get("status")),
                            stringValue(row.get("reason")),
                            decimalValue(row.get("price")),
                            decimalValue(row.get("bid_price")),
                            decimalValue(row.get("ask_price")),
                            decimalValue(row.get("configured_weight")),
                            decimalValue(row.get("effective_weight")),
                            instantValue(row.get("source_time")),
                            instantValue(row.get("received_at")),
                            longValue(row.get("latency_millis"))))
                    .toList();
        } catch (DataAccessException ex) {
            warnings.add(new MarketHealthWarning("sources", "price_index_components", ex.getMessage()));
            return List.of();
        }
    }

    private MarketSymbolHealth toSymbolHealth(Map<String, Object> row, Instant now, long staleSeconds) {
        BigDecimal indexPrice = decimalValue(row.get("index_price"));
        BigDecimal markPrice = decimalValue(row.get("mark_price"));
        Instant indexEventTime = instantValue(row.get("index_event_time"));
        Instant markEventTime = instantValue(row.get("mark_event_time"));
        Instant candleUpdatedAt = instantValue(row.get("candle_updated_at"));
        long indexAgeSeconds = ageSeconds(indexEventTime, now);
        long markAgeSeconds = ageSeconds(markEventTime, now);
        long candleAgeSeconds = ageSeconds(candleUpdatedAt, now);
        return new MarketSymbolHealth(
                stringValue(row.get("symbol")),
                stringValue(row.get("instrument_status")),
                stringValue(row.get("instrument_type")),
                stringValue(row.get("contract_type")),
                indexPrice,
                stringValue(row.get("index_status")),
                intValue(row.get("component_count")),
                intValue(row.get("valid_component_count")),
                indexEventTime,
                indexAgeSeconds,
                indexEventTime == null || indexAgeSeconds > staleSeconds,
                markPrice,
                decimalValue(row.get("mark_index_price")),
                longValue(row.get("mark_price_units")),
                decimalValue(row.get("funding_rate")),
                stringValue(row.get("mark_status")),
                markEventTime,
                markAgeSeconds,
                markEventTime == null || markAgeSeconds > staleSeconds,
                deviationPpm(markPrice, indexPrice),
                stringValue(row.get("candle_period")),
                stringValue(row.get("candle_status")),
                instantValue(row.get("candle_close_time")),
                candleUpdatedAt,
                longValue(row.get("candle_trade_count")),
                candleAgeSeconds,
                candleUpdatedAt == null || candleAgeSeconds > staleSeconds,
                longValue(row.get("source_count")),
                longValue(row.get("healthy_sources")),
                longValue(row.get("unhealthy_sources")),
                longValue(row.get("avg_source_latency_millis")),
                longValue(row.get("max_source_latency_millis")),
                stringValue(row.get("source_statuses")));
    }

    private MarketHealthSummary summary(List<MarketSymbolHealth> symbols) {
        long trading = symbols.stream().filter(row -> "TRADING".equals(row.instrumentStatus())).count();
        long unhealthyIndex = symbols.stream()
                .filter(row -> row.indexStale() || !"HEALTHY".equals(row.indexStatus()))
                .count();
        long unhealthyMark = symbols.stream()
                .filter(row -> row.markStale() || !"HEALTHY".equals(row.markStatus()))
                .count();
        long staleCandles = symbols.stream().filter(MarketSymbolHealth::candleStale).count();
        long sourceIssues = symbols.stream().filter(row -> row.unhealthySources() > 0).count();
        long maxIndexAge = symbols.stream().mapToLong(MarketSymbolHealth::indexAgeSeconds).max().orElse(0);
        long maxMarkAge = symbols.stream().mapToLong(MarketSymbolHealth::markAgeSeconds).max().orElse(0);
        long maxCandleAge = symbols.stream().mapToLong(MarketSymbolHealth::candleAgeSeconds).max().orElse(0);
        long maxSourceLatency = symbols.stream().mapToLong(MarketSymbolHealth::maxSourceLatencyMillis).max().orElse(0);
        long maxDeviation = symbols.stream().mapToLong(MarketSymbolHealth::markIndexDeviationPpm).max().orElse(0);
        return new MarketHealthSummary(symbols.size(), trading, unhealthyIndex, unhealthyMark, staleCandles,
                sourceIssues, maxIndexAge, maxMarkAge, maxCandleAge, maxSourceLatency, maxDeviation);
    }

    private long deviationPpm(BigDecimal markPrice, BigDecimal indexPrice) {
        if (markPrice == null || indexPrice == null || BigDecimal.ZERO.compareTo(indexPrice) == 0) {
            return 0;
        }
        return markPrice.subtract(indexPrice).abs()
                .multiply(BigDecimal.valueOf(1_000_000L))
                .divide(indexPrice.abs(), 0, java.math.RoundingMode.HALF_UP)
                .longValue();
    }

    private long ageSeconds(Instant value, Instant now) {
        if (value == null) {
            return 0;
        }
        return Math.max(0, Duration.between(value, now).getSeconds());
    }

    private String normalizeSymbol(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizePeriod(String value) {
        String normalized = value == null || value.isBlank() ? "1m" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[0-9]+[mhdw]$")) {
            throw new IllegalArgumentException("invalid candle period");
        }
        return normalized;
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private BigDecimal decimalValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            return new BigDecimal(text);
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Instant instantValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }

    public record MarketHealthResponse(
            Instant generatedAt,
            String period,
            long staleSeconds,
            MarketHealthSummary summary,
            List<MarketSymbolHealth> symbols,
            List<MarketSourceHealth> sources,
            List<MarketHealthWarning> warnings) {
    }

    public record MarketHealthSummary(
            long totalSymbols,
            long tradingSymbols,
            long unhealthyIndexSymbols,
            long unhealthyMarkSymbols,
            long staleCandleSymbols,
            long sourceIssueSymbols,
            long maxIndexAgeSeconds,
            long maxMarkAgeSeconds,
            long maxCandleAgeSeconds,
            long maxSourceLatencyMillis,
            long maxMarkIndexDeviationPpm) {
    }

    public record MarketSymbolHealth(
            String symbol,
            String instrumentStatus,
            String instrumentType,
            String contractType,
            BigDecimal indexPrice,
            String indexStatus,
            int componentCount,
            int validComponentCount,
            Instant indexEventTime,
            long indexAgeSeconds,
            boolean indexStale,
            BigDecimal markPrice,
            BigDecimal markIndexPrice,
            long markPriceUnits,
            BigDecimal fundingRate,
            String markStatus,
            Instant markEventTime,
            long markAgeSeconds,
            boolean markStale,
            long markIndexDeviationPpm,
            String candlePeriod,
            String candleStatus,
            Instant candleCloseTime,
            Instant candleUpdatedAt,
            long candleTradeCount,
            long candleAgeSeconds,
            boolean candleStale,
            long sourceCount,
            long healthySources,
            long unhealthySources,
            long avgSourceLatencyMillis,
            long maxSourceLatencyMillis,
            String sourceStatuses) {
    }

    public record MarketSourceHealth(
            String symbol,
            String source,
            String sourceSymbol,
            String status,
            String reason,
            BigDecimal price,
            BigDecimal bidPrice,
            BigDecimal askPrice,
            BigDecimal configuredWeight,
            BigDecimal effectiveWeight,
            Instant sourceTime,
            Instant receivedAt,
            long latencyMillis) {
    }

    public record MarketHealthWarning(
            String area,
            String source,
            String message) {
    }
}
