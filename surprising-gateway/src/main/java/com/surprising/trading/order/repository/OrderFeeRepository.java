package com.surprising.trading.order.repository;

import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.trading.api.model.FeeScheduleQueryResponse;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleSourceType;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.order.model.FeeSchedulePageQuery;
import com.surprising.product.api.ProductLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 订单费率仓储。
 *
 * <p>本仓储只负责 {@code trading_fee_schedules} 单表读写。下单费率解析由
 * {@code OrderFeeSnapshotLookup} 从 JVM 快照完成，禁止在热路径调用本仓储。</p>
 */
@Repository
public class OrderFeeRepository {

    private final JdbcTemplate jdbcTemplate;
    public OrderFeeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 启动恢复使用；下单热路径不得调用此方法。 */
    public List<FeeScheduleResponse> loadSnapshotSchedules(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_fee_schedules
                 WHERE product_line = ?
                 ORDER BY fee_schedule_id ASC
                """, (rs, rowNum) -> toResponse(rs), productLine.name());
    }

    /** Kafka 费率事实的异步数据库投影；订单请求线程不调用。 */
    public void project(FeeScheduleResponse schedule) {
        if (schedule == null || schedule.productLine() == null || schedule.feeScheduleId() <= 0L) {
            throw new IllegalArgumentException("费率投影事实无效");
        }
        Instant now = schedule.updatedAt() == null ? Instant.now() : schedule.updatedAt();
        Instant effectiveTime = schedule.effectiveTime() == null ? now : schedule.effectiveTime();
        jdbcTemplate.update("""
                INSERT INTO trading_fee_schedules (
                    fee_schedule_id, product_line, user_id, instrument_id, maker_fee_rate_ppm, taker_fee_rate_ppm,
                    source_type, tier_code, reason, status, effective_time, expire_time, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (fee_schedule_id) DO UPDATE SET
                    product_line = EXCLUDED.product_line,
                    user_id = EXCLUDED.user_id,
                    instrument_id = EXCLUDED.instrument_id,
                    maker_fee_rate_ppm = EXCLUDED.maker_fee_rate_ppm,
                    taker_fee_rate_ppm = EXCLUDED.taker_fee_rate_ppm,
                    source_type = EXCLUDED.source_type,
                    tier_code = EXCLUDED.tier_code,
                    reason = EXCLUDED.reason,
                    status = EXCLUDED.status,
                    effective_time = EXCLUDED.effective_time,
                    expire_time = EXCLUDED.expire_time,
                    updated_at = EXCLUDED.updated_at
                WHERE trading_fee_schedules.updated_at IS NULL
                   OR trading_fee_schedules.updated_at <= EXCLUDED.updated_at
                """, schedule.feeScheduleId(), schedule.productLine().name(), schedule.userId(),
                emptyToNull(schedule.instrumentId()), schedule.makerFeeRatePpm(), schedule.takerFeeRatePpm(),
                sourceType(schedule.sourceType()).name(), emptyToNull(schedule.tierCode()), schedule.reason(),
                status(schedule.status()).name(), Timestamp.from(effectiveTime),
                timestampOrNull(schedule.expireTime()), Timestamp.from(schedule.createdAt() == null ? now : schedule.createdAt()),
                Timestamp.from(now));
    }

    public FeeScheduleQueryResponse querySchedulesPage(ProductLine productLine,
                                                       long userId,
                                                       String instrumentId,
                                                       FeeScheduleStatus status,
                                                       FeeSchedulePageQuery query) {
        requireProductLine(productLine);
        int normalizedLimit = query.limit();
        String normalizedSymbol = instrumentId;
        String statusName = status == null ? null : status.name();
        AdminCursorPage.SortSpec sortSpec = scheduleSort(query.order());
        AdminCursorPage.Cursor decodedCursor = query.after();
        List<Object> args = new ArrayList<>();
        args.add(productLine.name());
        args.add(productLine.name());
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
                   AND (CAST(? AS text) IS NULL OR instrument_id = ?)
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

    private FeeScheduleResponse toResponse(ResultSet rs) throws SQLException {
        return new FeeScheduleResponse(
                rs.getLong("fee_schedule_id"),
                ProductLine.valueOf(rs.getString("product_line")),
                rs.getLong("user_id"),
                rs.getString("instrument_id"),
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

    private static ProductLine requireProductLine(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        return productLine;
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

    private static AdminCursorPage.SortSpec scheduleSort(FeeSchedulePageQuery.Order order) {
        String column = switch (order) {
            case UPDATED_DESC, UPDATED_ASC -> "updated_at";
            case CREATED_DESC, CREATED_ASC -> "created_at";
            case EFFECTIVE_DESC, EFFECTIVE_ASC -> "effective_time";
        };
        return new AdminCursorPage.SortSpec(order.field(), column, "fee_schedule_id", order.descending());
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
