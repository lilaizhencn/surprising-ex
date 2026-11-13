package com.surprising.liquidation.provider.repository;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.liquidation.provider.model.CoreLiquidationProjection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import org.springframework.stereotype.Repository;

@Repository
public class CoreLiquidationProjectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public CoreLiquidationProjectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ProjectionPage page(String productLine, Long userId, int limit, String cursor, String sort) {
        boolean descending = normalizeSort(sort);
        Cursor decoded = decodeCursor(cursor);
        String operator = descending ? "<" : ">";
        String direction = descending ? "DESC" : "ASC";
        StringBuilder sql = new StringBuilder("""
                SELECT liquidation_id, user_id, instrument_id, asset, margin_mode, position_side,
                       signed_quantity_steps, close_quantity_steps,
                       execution_price_ticks, liquidation_fee_rate_ppm,
                       liquidation_fee_units, status, updated_at_epoch_ms
                  FROM core_liquidation_projection
                 WHERE product_line = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(productLine);
        if (userId != null) {
            sql.append(" AND user_id = ?");
            args.add(userId);
        }
        if (decoded != null) {
            sql.append(" AND (updated_at_epoch_ms ").append(operator)
                    .append(" ? OR (updated_at_epoch_ms = ? AND liquidation_id ")
                    .append(operator).append(" ?))");
            args.add(decoded.updatedAtEpochMillis());
            args.add(decoded.updatedAtEpochMillis());
            args.add(decoded.liquidationId());
        }
        sql.append(" ORDER BY updated_at_epoch_ms ").append(direction)
                .append(", liquidation_id ").append(direction).append(" LIMIT ?");
        args.add(limit + 1);
        List<CoreLiquidationProjection> fetched = readAssociated(productLine, sql.toString(),
                "updated_at_epoch_ms " + direction + ",liquidation_id " + direction, (rs, rowNum) ->
                new CoreLiquidationProjection(rs.getLong("liquidation_id"), rs.getLong("user_id"),
                        rs.getString("instrument_id"), rs.getString("asset"),
                        CoreMarginMode.valueOf(rs.getString("margin_mode")),
                        CorePositionSide.valueOf(rs.getString("position_side")),
                        rs.getLong("signed_quantity_steps"),
                        rs.getLong("close_quantity_steps"),
                        rs.getLong("execution_price_ticks"), rs.getLong("liquidation_fee_rate_ppm"),
                        rs.getLong("liquidation_fee_units"), rs.getString("status"),
                        Instant.ofEpochMilli(rs.getLong("updated_at_epoch_ms"))), args.toArray());
        boolean hasMore = fetched.size() > limit;
        List<CoreLiquidationProjection> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = hasMore ? encodeCursor(items.getLast()) : null;
        return new ProjectionPage(items, nextCursor, hasMore, descending ? "createdAt.desc" : "createdAt.asc");
    }

    public List<CoreLiquidationProjection> byLiquidationId(String productLine, long liquidationId) {
        if (liquidationId <= 0) throw new IllegalArgumentException("candidateId must be positive");
        return readAssociated(productLine, """
                SELECT liquidation_id, user_id, instrument_id, asset, margin_mode, position_side,
                       signed_quantity_steps, close_quantity_steps,
                       execution_price_ticks, liquidation_fee_rate_ppm,
                       liquidation_fee_units, status, updated_at_epoch_ms
                  FROM core_liquidation_projection
                 WHERE product_line = ? AND liquidation_id = ?
                """, "liquidation_id", (rs, rowNum) -> new CoreLiquidationProjection(rs.getLong("liquidation_id"),
                rs.getLong("user_id"), rs.getString("instrument_id"), rs.getString("asset"),
                CoreMarginMode.valueOf(rs.getString("margin_mode")),
                CorePositionSide.valueOf(rs.getString("position_side")),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("close_quantity_steps"),
                rs.getLong("execution_price_ticks"), rs.getLong("liquidation_fee_rate_ppm"),
                rs.getLong("liquidation_fee_units"), rs.getString("status"),
                Instant.ofEpochMilli(rs.getLong("updated_at_epoch_ms"))), productLine, liquidationId);
    }

    /** 就绪标记与结果在同一 SQL 快照读取，空页不能掩盖未初始化。 */
    private List<CoreLiquidationProjection> readAssociated(String product, String sql, String orderBy,
            org.springframework.jdbc.core.RowMapper<CoreLiquidationProjection> mapper, Object... values) {
        var args = new ArrayList<Object>(); args.add(product); args.addAll(java.util.Arrays.asList(values));
        String associated = """
                SELECT initialized.__ready, projection_rows.*
                  FROM (SELECT count(*)=1 AS __ready FROM core_liquidation_projection_initialization WHERE product_line=?) initialized
                  LEFT JOIN LATERAL (
                """ + sql + ") projection_rows ON initialized.__ready ORDER BY " + orderBy;
        try {
            return jdbcTemplate.query(associated, (rs, row) -> {
                if (!rs.getBoolean("__ready")) throw new ReadViewUnavailableException("LIQUIDATION_READ_VIEW_NOT_READY");
                return rs.getObject("liquidation_id") == null ? null : mapper.mapRow(rs, row);
            }, args.toArray()).stream().filter(java.util.Objects::nonNull).toList();
        } catch (DataAccessException failure) {
            throw new ReadViewUnavailableException("LIQUIDATION_READ_VIEW_UNAVAILABLE", failure);
        } catch (IllegalArgumentException invalid) {
            throw new ReadViewUnavailableException("LIQUIDATION_READ_VIEW_INVALID", invalid);
        }
    }

    private static boolean normalizeSort(String sort) {
        if (sort == null || sort.isBlank() || "createdAt.desc".equals(sort.trim())) return true;
        if ("createdAt.asc".equals(sort.trim())) return false;
        throw new IllegalArgumentException("unsupported sort: " + sort);
    }

    private static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 2);
            if (parts.length != 2) throw new IllegalArgumentException("invalid cursor");
            return new Cursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid cursor", exception);
        }
    }

    private static String encodeCursor(CoreLiquidationProjection value) {
        String raw = value.updatedAt().toEpochMilli() + ":" + value.liquidationId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public record ProjectionPage(List<CoreLiquidationProjection> items, String nextCursor,
                                 boolean hasMore, String sort) {
    }

    private record Cursor(long updatedAtEpochMillis, long liquidationId) {
    }
}
