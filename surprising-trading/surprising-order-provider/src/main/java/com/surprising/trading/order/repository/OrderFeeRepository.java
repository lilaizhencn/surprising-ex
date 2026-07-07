package com.surprising.trading.order.repository;

import com.surprising.trading.api.model.AdminCursorPage;
import com.surprising.trading.api.model.FeeScheduleQueryResponse;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleSourceType;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineSql;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderFeeRepository {

    private static final long MAX_ABS_FEE_RATE_PPM = 1_000_000L;
    private static final int MAX_QUERY_LIMIT = 500;
    private static final AdminCursorPage.SortSpec SCHEDULE_UPDATED_DESC =
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "fee_schedule_id", true);
    private static final List<AdminCursorPage.SortSpec> SCHEDULE_SORTS = List.of(
            SCHEDULE_UPDATED_DESC,
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "fee_schedule_id", false),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "fee_schedule_id", true),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "fee_schedule_id", false),
            new AdminCursorPage.SortSpec("effectiveTime", "effective_time", "fee_schedule_id", true),
            new AdminCursorPage.SortSpec("effectiveTime", "effective_time", "fee_schedule_id", false));

    private final JdbcTemplate jdbcTemplate;

    public OrderFeeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OrderFeeSnapshot> snapshot(long userId, String symbol, long instrumentVersion, Instant now) {
        return jdbcTemplate.query("""
                WITH instrument_fee AS (
                    SELECT maker_fee_rate_ppm,
                           taker_fee_rate_ppm,
                           %s AS product_line
                      FROM instruments
                     WHERE symbol = ?
                       AND version = ?
                ),
                active_user_fee AS (
                    SELECT maker_fee_rate_ppm,
                           taker_fee_rate_ppm,
                           source_type,
                           CASE WHEN symbol = ? THEN 0 ELSE 1 END AS priority,
                           CASE
                               WHEN symbol IS NULL THEN source_type || '_GLOBAL'
                               ELSE source_type || '_SYMBOL'
                           END AS source,
                           CASE source_type
                               WHEN 'RISK_OVERRIDE' THEN 0
                               WHEN 'USER_OVERRIDE' THEN 1
                               WHEN 'PROMOTION' THEN 2
                               WHEN 'MARKET_MAKER' THEN 3
                               WHEN 'VIP' THEN 4
                               ELSE 5
                           END AS source_priority,
                           effective_time,
                           fee_schedule_id
                      FROM trading_fee_schedules
                     WHERE user_id = ?
                       AND product_line = (SELECT product_line FROM instrument_fee)
                       AND status = 'ACTIVE'
                       AND (symbol = ? OR symbol IS NULL)
                       AND effective_time <= ?
                       AND (expire_time IS NULL OR expire_time > ?)
                     ORDER BY priority ASC, source_priority ASC, effective_time DESC, fee_schedule_id DESC
                     LIMIT 1
                )
                SELECT COALESCE(u.maker_fee_rate_ppm, i.maker_fee_rate_ppm) AS maker_fee_rate_ppm,
                       COALESCE(u.taker_fee_rate_ppm, i.taker_fee_rate_ppm) AS taker_fee_rate_ppm,
                       i.product_line,
                       COALESCE(u.source, 'INSTRUMENT') AS source
                  FROM instrument_fee i
             LEFT JOIN active_user_fee u ON TRUE
                """.formatted(ProductLineSql.contractTypeProductLineCase("contract_type")),
                (rs, rowNum) -> new OrderFeeSnapshot(
                ProductLine.valueOf(rs.getString("product_line")),
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm"),
                rs.getString("source")), symbol, instrumentVersion, symbol, userId, symbol,
                Timestamp.from(now), Timestamp.from(now)).stream().findFirst();
    }

    public void upsertSchedule(FeeScheduleUpsertRequest request, long feeScheduleId, Instant now) {
        Instant effectiveTime = request.effectiveTime() == null ? now : request.effectiveTime();
        ProductLine productLine = productLine(request.productLine());
        jdbcTemplate.update("""
                INSERT INTO trading_fee_schedules (
                    fee_schedule_id, product_line, user_id, symbol, maker_fee_rate_ppm, taker_fee_rate_ppm,
                    source_type, tier_code, reason, status, effective_time, expire_time, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (fee_schedule_id) DO UPDATE SET
                    product_line = EXCLUDED.product_line,
                    user_id = EXCLUDED.user_id,
                    symbol = EXCLUDED.symbol,
                    maker_fee_rate_ppm = EXCLUDED.maker_fee_rate_ppm,
                    taker_fee_rate_ppm = EXCLUDED.taker_fee_rate_ppm,
                    source_type = EXCLUDED.source_type,
                    tier_code = EXCLUDED.tier_code,
                    reason = EXCLUDED.reason,
                    status = EXCLUDED.status,
                    effective_time = EXCLUDED.effective_time,
                    expire_time = EXCLUDED.expire_time,
                    updated_at = EXCLUDED.updated_at
                """, feeScheduleId, productLine.name(), request.userId(), emptyToNull(request.symbol()),
                request.makerFeeRatePpm(), request.takerFeeRatePpm(), sourceType(request.sourceType()).name(),
                emptyToNull(request.tierCode()), request.reason().trim(), status(request.status()).name(),
                Timestamp.from(effectiveTime), timestampOrNull(request.expireTime()),
                Timestamp.from(now), Timestamp.from(now));
    }

    public boolean disableSchedule(long feeScheduleId, Instant now) {
        return disableSchedule(feeScheduleId, null, now);
    }

    public boolean disableSchedule(long feeScheduleId, ProductLine productLine, Instant now) {
        return jdbcTemplate.update("""
                UPDATE trading_fee_schedules
                   SET status = 'DISABLED',
                       updated_at = ?
                 WHERE fee_schedule_id = ?
                   AND (CAST(? AS text) IS NULL OR product_line = ?)
                   AND status <> 'DISABLED'
                """, Timestamp.from(now), feeScheduleId,
                productLine == null ? null : productLine.name(),
                productLine == null ? null : productLine.name()) == 1;
    }

    public Optional<FeeScheduleResponse> findSchedule(long feeScheduleId) {
        return findSchedule(feeScheduleId, null);
    }

    public Optional<FeeScheduleResponse> findSchedule(long feeScheduleId, ProductLine productLine) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_fee_schedules
                 WHERE fee_schedule_id = ?
                   AND (CAST(? AS text) IS NULL OR product_line = ?)
                """, (rs, rowNum) -> toResponse(rs), feeScheduleId,
                productLine == null ? null : productLine.name(),
                productLine == null ? null : productLine.name()).stream().findFirst();
    }

    public FeeScheduleQueryResponse querySchedules(long userId, String symbol, FeeScheduleStatus status, int limit) {
        return querySchedules(null, userId, symbol, status, limit);
    }

    public FeeScheduleQueryResponse querySchedules(ProductLine productLine,
                                                   long userId,
                                                   String symbol,
                                                   FeeScheduleStatus status,
                                                   int limit) {
        int normalizedLimit = AdminCursorPage.limit(limit, MAX_QUERY_LIMIT);
        String normalizedSymbol = emptyToNull(symbol);
        String statusName = status == null ? null : status.name();
        List<FeeScheduleResponse> schedules = jdbcTemplate.query("""
                SELECT *
                  FROM trading_fee_schedules
                 WHERE (CAST(? AS text) IS NULL OR product_line = ?)
                   AND (? <= 0 OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                 ORDER BY product_line ASC, user_id ASC, symbol ASC NULLS FIRST, effective_time DESC, fee_schedule_id DESC
                 LIMIT ?
                """, (rs, rowNum) -> toResponse(rs),
                productLine == null ? null : productLine.name(),
                productLine == null ? null : productLine.name(),
                userId, userId, normalizedSymbol, normalizedSymbol,
                statusName, statusName, normalizedLimit);
        return new FeeScheduleQueryResponse(schedules.size(), schedules);
    }

    public FeeScheduleQueryResponse querySchedulesPage(long userId,
                                                       String symbol,
                                                       FeeScheduleStatus status,
                                                       int limit,
                                                       String cursor,
                                                       String sort) {
        return querySchedulesPage(null, userId, symbol, status, limit, cursor, sort);
    }

    public FeeScheduleQueryResponse querySchedulesPage(ProductLine productLine,
                                                       long userId,
                                                       String symbol,
                                                       FeeScheduleStatus status,
                                                       int limit,
                                                       String cursor,
                                                       String sort) {
        int normalizedLimit = AdminCursorPage.limit(limit, MAX_QUERY_LIMIT);
        String normalizedSymbol = emptyToNull(symbol);
        String statusName = status == null ? null : status.name();
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, SCHEDULE_UPDATED_DESC, SCHEDULE_SORTS);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(productLine == null ? null : productLine.name());
        args.add(productLine == null ? null : productLine.name());
        args.add(userId);
        args.add(userId);
        args.add(normalizedSymbol);
        args.add(normalizedSymbol);
        args.add(statusName);
        args.add(statusName);
        String sql = """
                SELECT *
                  FROM trading_fee_schedules
                 WHERE (CAST(? AS text) IS NULL OR product_line = ?)
                   AND (? <= 0 OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY %s %s, fee_schedule_id %s
                 LIMIT ?
                """.formatted(sortSpec.column(), sortSpec.directionSql(), sortSpec.directionSql());
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(normalizedLimit + 1);
        List<FeeScheduleResponse> fetchedRows = jdbcTemplate.query(sql, (rs, rowNum) -> toResponse(rs),
                args.toArray());
        AdminCursorPage.CursorPage<FeeScheduleResponse> page = AdminCursorPage.page(
                fetchedRows,
                normalizedLimit,
                sortSpec,
                scheduleTimestampExtractor(sortSpec),
                FeeScheduleResponse::feeScheduleId);
        return new FeeScheduleQueryResponse(page.items().size(), page.items(), page.nextCursor(), page.hasMore(),
                page.sort(), page.limit());
    }

    public static void validateSchedule(FeeScheduleUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("fee schedule request is required");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        productLine(request.productLine());
        validateSymbol(request.symbol());
        validateFeeRate(request.makerFeeRatePpm(), "makerFeeRatePpm");
        validateFeeRate(request.takerFeeRatePpm(), "takerFeeRatePpm");
        if (request.makerFeeRatePpm() > request.takerFeeRatePpm()) {
            throw new IllegalArgumentException("makerFeeRatePpm cannot exceed takerFeeRatePpm");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        if (request.tierCode() != null && !request.tierCode().isBlank()
                && !request.tierCode().trim().toUpperCase().matches("[A-Z0-9][A-Z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("invalid tierCode: " + request.tierCode());
        }
        Instant effectiveTime = request.effectiveTime();
        Instant expireTime = request.expireTime();
        if (effectiveTime != null && expireTime != null && !expireTime.isAfter(effectiveTime)) {
            throw new IllegalArgumentException("expireTime must be after effectiveTime");
        }
    }

    private FeeScheduleResponse toResponse(ResultSet rs) throws SQLException {
        return new FeeScheduleResponse(
                rs.getLong("fee_schedule_id"),
                ProductLine.valueOf(rs.getString("product_line")),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm"),
                FeeScheduleSourceType.valueOf(rs.getString("source_type")),
                rs.getString("tier_code"),
                rs.getString("reason"),
                FeeScheduleStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("effective_time").toInstant(),
                timestampInstantOrNull(rs, "expire_time"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static FeeScheduleSourceType sourceType(FeeScheduleSourceType sourceType) {
        return sourceType == null ? FeeScheduleSourceType.USER_OVERRIDE : sourceType;
    }

    private static FeeScheduleStatus status(FeeScheduleStatus status) {
        return status == null ? FeeScheduleStatus.ACTIVE : status;
    }

    private static ProductLine productLine(ProductLine productLine) {
        return productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }

    private static void validateFeeRate(long feeRatePpm, String field) {
        if (feeRatePpm < -MAX_ABS_FEE_RATE_PPM || feeRatePpm > MAX_ABS_FEE_RATE_PPM) {
            throw new IllegalArgumentException(field + " must be within +/- 100%");
        }
    }

    private static void validateSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        if (!symbol.trim().toUpperCase().matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
    }

    private static Timestamp timestampOrNull(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant timestampInstantOrNull(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase();
    }

    private static Function<FeeScheduleResponse, Instant> scheduleTimestampExtractor(
            AdminCursorPage.SortSpec sort) {
        return switch (sort.field()) {
            case "createdAt" -> FeeScheduleResponse::createdAt;
            case "effectiveTime" -> FeeScheduleResponse::effectiveTime;
            case "updatedAt" -> FeeScheduleResponse::updatedAt;
            default -> throw new IllegalArgumentException("unsupported sort: " + sort.token());
        };
    }
}
