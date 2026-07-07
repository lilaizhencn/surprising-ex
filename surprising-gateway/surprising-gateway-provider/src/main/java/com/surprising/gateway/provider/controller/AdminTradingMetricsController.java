package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.product.api.ProductLine;
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
@RequestMapping("/api/v1/admin/trading")
public class AdminTradingMetricsController {

    private final AuthService authService;
    private final JdbcTemplate jdbcTemplate;

    public AdminTradingMetricsController(AuthService authService, JdbcTemplate jdbcTemplate) {
        this.authService = authService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/metrics")
    public TradingMetricsResponse metrics(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                          @RequestHeader(value = "X-Product-Line", required = false)
                                          String productLineHeader,
                                          @RequestParam(value = "productLine", required = false)
                                          String productLineValue,
                                          @RequestParam(value = "windowMinutes", defaultValue = "1440") int windowMinutes,
                                          @RequestParam(value = "limit", defaultValue = "20") int limit) {
        try {
            authService.requireAdminPermission(authorization, "admin.trading.read");
            int boundedWindow = Math.max(1, Math.min(windowMinutes, 43_200));
            int boundedLimit = Math.max(1, Math.min(limit, 100));
            String contractType = contractType(productLine(productLineValue, productLineHeader));
            Instant now = Instant.now();
            Instant since = now.minus(Duration.ofMinutes(boundedWindow));
            List<TradingMetricWarning> warnings = new ArrayList<>();
            return new TradingMetricsResponse(
                    now,
                    boundedWindow,
                    orderMetrics(since, contractType, warnings),
                    tradeMetrics(since, contractType, warnings),
                    matchingMetrics(since, contractType, warnings),
                    triggerMetrics(now, since, contractType, warnings),
                    positionMetrics(contractType, warnings),
                    symbolMetrics(since, boundedLimit, contractType, warnings),
                    warnings);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private OrderMetrics orderMetrics(Instant since, String contractType, List<TradingMetricWarning> warnings) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) FILTER (WHERE created_at >= ?) AS submitted,
                           COUNT(DISTINCT user_id) FILTER (WHERE created_at >= ?) AS unique_users,
                           COUNT(*) FILTER (WHERE created_at >= ? AND status = 'ACCEPTED') AS accepted,
                           COUNT(*) FILTER (WHERE created_at >= ? AND status = 'PARTIALLY_FILLED') AS partially_filled,
                           COUNT(*) FILTER (WHERE created_at >= ? AND status = 'FILLED') AS filled,
                           COUNT(*) FILTER (WHERE created_at >= ? AND status = 'CANCEL_REQUESTED') AS cancel_requested,
                           COUNT(*) FILTER (WHERE created_at >= ? AND status = 'CANCELED') AS canceled,
                           COUNT(*) FILTER (WHERE created_at >= ? AND status = 'REJECTED') AS rejected,
                           COUNT(*) FILTER (
                               WHERE status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                           ) AS open_orders,
                           COALESCE(SUM(remaining_quantity_steps) FILTER (
                               WHERE status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                           ), 0) AS open_quantity_steps,
                           MAX(created_at) FILTER (WHERE created_at >= ?) AS last_submitted_at,
                           MAX(updated_at) FILTER (
                               WHERE status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                           ) AS last_open_updated_at
                      FROM trading_orders
                     WHERE (CAST(? AS text) IS NULL OR EXISTS (
                            SELECT 1
                              FROM instruments i
                             WHERE i.symbol = trading_orders.symbol
                               AND i.version = trading_orders.instrument_version
                               AND i.contract_type = ?
                     ))
                    """, timestamp(since), timestamp(since), timestamp(since), timestamp(since), timestamp(since),
                    timestamp(since), timestamp(since), timestamp(since), timestamp(since),
                    contractType, contractType);
            long submitted = longValue(row.get("submitted"));
            long rejected = longValue(row.get("rejected"));
            List<OrderStatusMetric> statuses = jdbcTemplate.queryForList("""
                    SELECT status, COUNT(*) AS total
                      FROM trading_orders
                     WHERE created_at >= ?
                       AND (CAST(? AS text) IS NULL OR EXISTS (
                            SELECT 1
                              FROM instruments i
                             WHERE i.symbol = trading_orders.symbol
                               AND i.version = trading_orders.instrument_version
                               AND i.contract_type = ?
                       ))
                     GROUP BY status
                     ORDER BY total DESC, status
                    """, timestamp(since), contractType, contractType).stream()
                    .map(status -> new OrderStatusMetric(stringValue(status.get("status")), longValue(status.get("total"))))
                    .toList();
            return new OrderMetrics(
                    submitted,
                    longValue(row.get("unique_users")),
                    longValue(row.get("accepted")),
                    longValue(row.get("partially_filled")),
                    longValue(row.get("filled")),
                    longValue(row.get("cancel_requested")),
                    longValue(row.get("canceled")),
                    rejected,
                    failureRatePpm(submitted, rejected),
                    longValue(row.get("open_orders")),
                    longValue(row.get("open_quantity_steps")),
                    instantValue(row.get("last_submitted_at")),
                    instantValue(row.get("last_open_updated_at")),
                    statuses,
                    null);
        } catch (DataAccessException ex) {
            warnings.add(new TradingMetricWarning("orders", "trading_orders", ex.getMessage()));
            return new OrderMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, null, null, List.of(), ex.getMessage());
        }
    }

    private TradeMetrics tradeMetrics(Instant since, String contractType, List<TradingMetricWarning> warnings) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) AS trades,
                           COALESCE(SUM(quantity_steps), 0) AS volume_steps,
                           COALESCE(SUM(price_ticks::numeric * quantity_steps::numeric), 0) AS notional_ticks_steps,
                           COUNT(DISTINCT taker_user_id) AS taker_users,
                           COUNT(DISTINCT maker_user_id) AS maker_users,
                           MAX(event_time) AS last_trade_at
                      FROM trading_match_trades
                     WHERE event_time >= ?
                       AND (CAST(? AS text) IS NULL OR EXISTS (
                            SELECT 1
                              FROM instruments i
                             WHERE i.symbol = trading_match_trades.symbol
                               AND i.version = trading_match_trades.taker_instrument_version
                               AND i.contract_type = ?
                       ))
                    """, timestamp(since), contractType, contractType);
            Map<String, Object> participants = jdbcTemplate.queryForMap("""
                    SELECT COUNT(DISTINCT user_id) AS unique_participants
                      FROM (
                            SELECT taker_user_id AS user_id
                              FROM trading_match_trades
                             WHERE event_time >= ?
                               AND (CAST(? AS text) IS NULL OR EXISTS (
                                    SELECT 1
                                      FROM instruments i
                                     WHERE i.symbol = trading_match_trades.symbol
                                       AND i.version = trading_match_trades.taker_instrument_version
                                       AND i.contract_type = ?
                               ))
                            UNION
                            SELECT maker_user_id AS user_id
                              FROM trading_match_trades
                             WHERE event_time >= ?
                               AND (CAST(? AS text) IS NULL OR EXISTS (
                                    SELECT 1
                                      FROM instruments i
                                     WHERE i.symbol = trading_match_trades.symbol
                                       AND i.version = trading_match_trades.maker_instrument_version
                                       AND i.contract_type = ?
                               ))
                      ) participants
                    """, timestamp(since), contractType, contractType,
                    timestamp(since), contractType, contractType);
            return new TradeMetrics(
                    longValue(row.get("trades")),
                    longValue(row.get("volume_steps")),
                    decimalValue(row.get("notional_ticks_steps")),
                    longValue(row.get("taker_users")),
                    longValue(row.get("maker_users")),
                    longValue(participants.get("unique_participants")),
                    instantValue(row.get("last_trade_at")),
                    null);
        } catch (DataAccessException ex) {
            warnings.add(new TradingMetricWarning("trades", "trading_match_trades", ex.getMessage()));
            return new TradeMetrics(0, 0, BigDecimal.ZERO, 0, 0, 0, null, ex.getMessage());
        }
    }

    private MatchingMetrics matchingMetrics(Instant since, String contractType, List<TradingMetricWarning> warnings) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) AS commands,
                           COUNT(*) FILTER (WHERE result_code = 'SUCCESS') AS successful_commands,
                           COUNT(*) FILTER (WHERE result_code <> 'SUCCESS') AS rejected_commands,
                           COUNT(*) FILTER (WHERE command_type = 'PLACE') AS place_commands,
                           COUNT(*) FILTER (WHERE command_type = 'CANCEL') AS cancel_commands,
                           MAX(event_time) AS last_result_at
                      FROM trading_match_results
                     WHERE event_time >= ?
                       AND (CAST(? AS text) IS NULL OR EXISTS (
                            SELECT 1
                              FROM instruments i
                             WHERE i.symbol = trading_match_results.symbol
                               AND i.version = trading_match_results.instrument_version
                               AND i.contract_type = ?
                       ))
                    """, timestamp(since), contractType, contractType);
            long commands = longValue(row.get("commands"));
            long rejected = longValue(row.get("rejected_commands"));
            return new MatchingMetrics(
                    commands,
                    longValue(row.get("successful_commands")),
                    rejected,
                    failureRatePpm(commands, rejected),
                    longValue(row.get("place_commands")),
                    longValue(row.get("cancel_commands")),
                    instantValue(row.get("last_result_at")),
                    null);
        } catch (DataAccessException ex) {
            warnings.add(new TradingMetricWarning("matching", "trading_match_results", ex.getMessage()));
            return new MatchingMetrics(0, 0, 0, 0, 0, 0, null, ex.getMessage());
        }
    }

    private TriggerMetrics triggerMetrics(Instant now, Instant since, String contractType, List<TradingMetricWarning> warnings) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) FILTER (WHERE created_at >= ?) AS created,
                           COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                           COUNT(*) FILTER (WHERE status = 'TRIGGERING') AS triggering,
                           COUNT(*) FILTER (WHERE status = 'TRIGGERED' AND updated_at >= ?) AS triggered,
                           COUNT(*) FILTER (WHERE status = 'TRIGGER_FAILED' AND updated_at >= ?) AS failed,
                           COUNT(*) FILTER (WHERE status = 'EXPIRED' AND updated_at >= ?) AS expired,
                           COUNT(*) FILTER (WHERE status = 'CANCELED' AND updated_at >= ?) AS canceled,
                           COUNT(*) FILTER (WHERE status = 'PENDING' AND expires_at <= ?) AS expired_pending,
                           MAX(updated_at) AS last_updated_at
                      FROM trading_trigger_orders
                     WHERE (CAST(? AS text) IS NULL OR EXISTS (
                            SELECT 1
                              FROM instruments i
                             WHERE i.symbol = trading_trigger_orders.symbol
                               AND i.contract_type = ?
                     ))
                    """, timestamp(since), timestamp(since), timestamp(since), timestamp(since), timestamp(since),
                    timestamp(now), contractType, contractType);
            return new TriggerMetrics(
                    longValue(row.get("created")),
                    longValue(row.get("pending")),
                    longValue(row.get("triggering")),
                    longValue(row.get("triggered")),
                    longValue(row.get("failed")),
                    longValue(row.get("expired")),
                    longValue(row.get("canceled")),
                    longValue(row.get("expired_pending")),
                    instantValue(row.get("last_updated_at")),
                    null);
        } catch (DataAccessException ex) {
            warnings.add(new TradingMetricWarning("triggerOrders", "trading_trigger_orders", ex.getMessage()));
            return new TriggerMetrics(0, 0, 0, 0, 0, 0, 0, 0, null, ex.getMessage());
        }
    }

    private PositionMetrics positionMetrics(String contractType, List<TradingMetricWarning> warnings) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) FILTER (WHERE signed_quantity_steps <> 0) AS open_positions,
                           COUNT(DISTINCT user_id) FILTER (WHERE signed_quantity_steps <> 0) AS users_with_positions,
                           COUNT(*) FILTER (WHERE signed_quantity_steps > 0) AS long_positions,
                           COUNT(*) FILTER (WHERE signed_quantity_steps < 0) AS short_positions,
                           COALESCE(SUM(GREATEST(signed_quantity_steps, 0)), 0) AS long_quantity_steps,
                           COALESCE(SUM(ABS(LEAST(signed_quantity_steps, 0))), 0) AS short_quantity_steps,
                           MAX(updated_at) FILTER (WHERE signed_quantity_steps <> 0) AS last_position_updated_at
                      FROM account_positions
                     WHERE (CAST(? AS text) IS NULL OR EXISTS (
                            SELECT 1
                              FROM instruments i
                             WHERE i.symbol = account_positions.symbol
                               AND i.version = account_positions.instrument_version
                               AND i.contract_type = ?
                     ))
                    """, contractType, contractType);
            List<PositionSymbolMetric> symbols = jdbcTemplate.queryForList("""
                    SELECT symbol,
                           COUNT(*) AS open_positions,
                           COUNT(DISTINCT user_id) AS users,
                           COUNT(*) FILTER (WHERE signed_quantity_steps > 0) AS long_positions,
                           COUNT(*) FILTER (WHERE signed_quantity_steps < 0) AS short_positions,
                           COALESCE(SUM(GREATEST(signed_quantity_steps, 0)), 0) AS long_quantity_steps,
                           COALESCE(SUM(ABS(LEAST(signed_quantity_steps, 0))), 0) AS short_quantity_steps,
                           MAX(updated_at) AS last_updated_at
                      FROM account_positions
                     WHERE signed_quantity_steps <> 0
                       AND (CAST(? AS text) IS NULL OR EXISTS (
                            SELECT 1
                              FROM instruments i
                             WHERE i.symbol = account_positions.symbol
                               AND i.version = account_positions.instrument_version
                               AND i.contract_type = ?
                       ))
                     GROUP BY symbol
                     ORDER BY GREATEST(
                              COALESCE(SUM(GREATEST(signed_quantity_steps, 0)), 0),
                              COALESCE(SUM(ABS(LEAST(signed_quantity_steps, 0))), 0)
                            ) DESC,
                              open_positions DESC,
                              symbol
                     LIMIT 20
                    """, contractType, contractType).stream()
                    .map(symbol -> new PositionSymbolMetric(
                            stringValue(symbol.get("symbol")),
                            longValue(symbol.get("open_positions")),
                            longValue(symbol.get("users")),
                            longValue(symbol.get("long_positions")),
                            longValue(symbol.get("short_positions")),
                            longValue(symbol.get("long_quantity_steps")),
                            longValue(symbol.get("short_quantity_steps")),
                            instantValue(symbol.get("last_updated_at"))))
                    .toList();
            return new PositionMetrics(
                    longValue(row.get("open_positions")),
                    longValue(row.get("users_with_positions")),
                    longValue(row.get("long_positions")),
                    longValue(row.get("short_positions")),
                    longValue(row.get("long_quantity_steps")),
                    longValue(row.get("short_quantity_steps")),
                    instantValue(row.get("last_position_updated_at")),
                    symbols,
                    null);
        } catch (DataAccessException ex) {
            warnings.add(new TradingMetricWarning("positions", "account_positions", ex.getMessage()));
            return new PositionMetrics(0, 0, 0, 0, 0, 0, null, List.of(), ex.getMessage());
        }
    }

    private List<SymbolTradingMetric> symbolMetrics(Instant since,
                                                    int limit,
                                                    String contractType,
                                                    List<TradingMetricWarning> warnings) {
        try {
            return jdbcTemplate.queryForList("""
                    WITH order_stats AS (
                        SELECT symbol,
                               COUNT(*) FILTER (WHERE created_at >= ?) AS submitted_orders,
                               COUNT(DISTINCT user_id) FILTER (WHERE created_at >= ?) AS order_users,
                               COUNT(*) FILTER (WHERE created_at >= ? AND status = 'REJECTED') AS rejected_orders,
                               COUNT(*) FILTER (
                                   WHERE status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                               ) AS open_orders,
                               COALESCE(SUM(remaining_quantity_steps) FILTER (
                                   WHERE status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                               ), 0) AS open_quantity_steps
                          FROM trading_orders
                         WHERE (created_at >= ?
                            OR status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED'))
                           AND (CAST(? AS text) IS NULL OR EXISTS (
                                SELECT 1
                                  FROM instruments i
                                 WHERE i.symbol = trading_orders.symbol
                                   AND i.version = trading_orders.instrument_version
                                   AND i.contract_type = ?
                           ))
                         GROUP BY symbol
                    ),
                    trade_stats AS (
                        SELECT symbol,
                               COUNT(*) AS trades,
                               COALESCE(SUM(quantity_steps), 0) AS volume_steps,
                               COALESCE(SUM(price_ticks::numeric * quantity_steps::numeric), 0) AS notional_ticks_steps,
                               MAX(event_time) AS last_trade_at,
                               (ARRAY_AGG(price_ticks ORDER BY event_time DESC, trade_id DESC))[1] AS last_trade_price_ticks
                          FROM trading_match_trades
                         WHERE event_time >= ?
                           AND (CAST(? AS text) IS NULL OR EXISTS (
                                SELECT 1
                                  FROM instruments i
                                 WHERE i.symbol = trading_match_trades.symbol
                                   AND i.version = trading_match_trades.taker_instrument_version
                                   AND i.contract_type = ?
                           ))
                         GROUP BY symbol
                    ),
                    open_interest AS (
                        SELECT symbol, long_quantity_steps, short_quantity_steps, open_quantity_steps
                          FROM trading_symbol_open_interest
                         WHERE (CAST(? AS text) IS NULL OR EXISTS (
                                SELECT 1
                                  FROM instrument_current_versions cv
                                  JOIN instruments i ON i.symbol = cv.symbol AND i.version = cv.version
                                 WHERE cv.symbol = trading_symbol_open_interest.symbol
                                   AND i.contract_type = ?
                         ))
                    )
                    SELECT COALESCE(trade_stats.symbol, order_stats.symbol, open_interest.symbol) AS symbol,
                           COALESCE(order_stats.submitted_orders, 0) AS submitted_orders,
                           COALESCE(order_stats.order_users, 0) AS order_users,
                           COALESCE(order_stats.rejected_orders, 0) AS rejected_orders,
                           COALESCE(order_stats.open_orders, 0) AS open_orders,
                           COALESCE(order_stats.open_quantity_steps, 0) AS open_order_quantity_steps,
                           COALESCE(trade_stats.trades, 0) AS trades,
                           COALESCE(trade_stats.volume_steps, 0) AS volume_steps,
                           COALESCE(trade_stats.notional_ticks_steps, 0) AS notional_ticks_steps,
                           trade_stats.last_trade_at,
                           trade_stats.last_trade_price_ticks,
                           COALESCE(open_interest.long_quantity_steps, 0) AS open_interest_long_steps,
                           COALESCE(open_interest.short_quantity_steps, 0) AS open_interest_short_steps,
                           COALESCE(open_interest.open_quantity_steps, 0) AS open_interest_steps
                      FROM trade_stats
                      FULL OUTER JOIN order_stats ON order_stats.symbol = trade_stats.symbol
                      FULL OUTER JOIN open_interest
                        ON open_interest.symbol = COALESCE(trade_stats.symbol, order_stats.symbol)
                     ORDER BY COALESCE(trade_stats.notional_ticks_steps, 0) DESC,
                              COALESCE(trade_stats.trades, 0) DESC,
                              COALESCE(order_stats.open_orders, 0) DESC,
                             symbol
                     LIMIT ?
                    """, timestamp(since), timestamp(since), timestamp(since), timestamp(since),
                    contractType, contractType, timestamp(since), contractType, contractType,
                    contractType, contractType, limit).stream()
                    .map(row -> new SymbolTradingMetric(
                            stringValue(row.get("symbol")),
                            longValue(row.get("submitted_orders")),
                            longValue(row.get("order_users")),
                            longValue(row.get("rejected_orders")),
                            longValue(row.get("open_orders")),
                            longValue(row.get("open_order_quantity_steps")),
                            longValue(row.get("trades")),
                            longValue(row.get("volume_steps")),
                            decimalValue(row.get("notional_ticks_steps")),
                            instantValue(row.get("last_trade_at")),
                            longValue(row.get("last_trade_price_ticks")),
                            longValue(row.get("open_interest_long_steps")),
                            longValue(row.get("open_interest_short_steps")),
                            longValue(row.get("open_interest_steps"))))
                    .toList();
        } catch (DataAccessException ex) {
            warnings.add(new TradingMetricWarning("symbols", "trading_symbol_open_interest", ex.getMessage()));
            return List.of();
        }
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private ProductLine productLine(String queryValue, String headerValue) {
        String value = firstNonBlank(queryValue, headerValue);
        if (value == null) {
            return null;
        }
        ProductLine byAccountType = ProductLine.fromAccountTypeCode(value).orElse(null);
        if (byAccountType != null) {
            return byAccountType;
        }
        ProductLine byContractType = ProductLine.fromContractTypeCode(value).orElse(null);
        if (byContractType != null) {
            return byContractType;
        }
        String enumName = value.toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace('.', '_');
        for (ProductLine productLine : ProductLine.values()) {
            if (productLine.name().equals(enumName)
                    || productLine.topicSegment().equalsIgnoreCase(value)) {
                return productLine;
            }
        }
        throw new IllegalArgumentException("unsupported productLine: " + value);
    }

    private String contractType(ProductLine productLine) {
        return productLine == null ? null : productLine.contractTypeCode();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private long failureRatePpm(long total, long failed) {
        if (total <= 0 || failed <= 0) {
            return 0;
        }
        return Math.round((failed * 1_000_000.0d) / total);
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            return new BigDecimal(text);
        }
        return BigDecimal.ZERO;
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

    public record TradingMetricsResponse(
            Instant generatedAt,
            int windowMinutes,
            OrderMetrics orders,
            TradeMetrics trades,
            MatchingMetrics matching,
            TriggerMetrics triggerOrders,
            PositionMetrics positions,
            List<SymbolTradingMetric> symbols,
            List<TradingMetricWarning> warnings) {
    }

    public record OrderMetrics(
            long submitted,
            long uniqueUsers,
            long accepted,
            long partiallyFilled,
            long filled,
            long cancelRequested,
            long canceled,
            long rejected,
            long rejectRatePpm,
            long openOrders,
            long openQuantitySteps,
            Instant lastSubmittedAt,
            Instant lastOpenUpdatedAt,
            List<OrderStatusMetric> statuses,
            String error) {
    }

    public record OrderStatusMetric(
            String status,
            long total) {
    }

    public record TradeMetrics(
            long trades,
            long volumeSteps,
            BigDecimal notionalTicksSteps,
            long takerUsers,
            long makerUsers,
            long uniqueParticipants,
            Instant lastTradeAt,
            String error) {
    }

    public record MatchingMetrics(
            long commands,
            long successfulCommands,
            long rejectedCommands,
            long rejectRatePpm,
            long placeCommands,
            long cancelCommands,
            Instant lastResultAt,
            String error) {
    }

    public record TriggerMetrics(
            long created,
            long pending,
            long triggering,
            long triggered,
            long failed,
            long expired,
            long canceled,
            long expiredPending,
            Instant lastUpdatedAt,
            String error) {
    }

    public record PositionMetrics(
            long openPositions,
            long usersWithPositions,
            long longPositions,
            long shortPositions,
            long longQuantitySteps,
            long shortQuantitySteps,
            Instant lastPositionUpdatedAt,
            List<PositionSymbolMetric> symbols,
            String error) {
    }

    public record PositionSymbolMetric(
            String symbol,
            long openPositions,
            long users,
            long longPositions,
            long shortPositions,
            long longQuantitySteps,
            long shortQuantitySteps,
            Instant lastUpdatedAt) {
    }

    public record SymbolTradingMetric(
            String symbol,
            long submittedOrders,
            long orderUsers,
            long rejectedOrders,
            long openOrders,
            long openOrderQuantitySteps,
            long trades,
            long volumeSteps,
            BigDecimal notionalTicksSteps,
            Instant lastTradeAt,
            long lastTradePriceTicks,
            long openInterestLongSteps,
            long openInterestShortSteps,
            long openInterestSteps) {
    }

    public record TradingMetricWarning(
            String area,
            String source,
            String message) {
    }
}
