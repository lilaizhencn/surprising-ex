package com.surprising.trading.trigger.repository;

import com.surprising.aeron.protocol.CoreTriggerOrderCodec;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;

/** Reads realtime committed trigger facts with their documented initialization association. */
@Repository
public final class TriggerOrderProjectionRepository {
    private final JdbcTemplate jdbc;
    public TriggerOrderProjectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<CoreTriggerOrderStateView> get(ProductLine product, Long userId, long triggerId) {
        if (product == null || triggerId <= 0 || userId != null && userId <= 0)
            throw new IllegalArgumentException("invalid trigger query identity");
        try {
            var args = new ArrayList<Object>(List.of(product.name(), triggerId));
            String user = userId == null ? "" : " AND user_id=?";
            if (userId != null) args.add(userId);
            return readAssociated(product, "SELECT * FROM core_trigger_order_projection WHERE product_line=? AND trigger_order_id=?" + user,
                    "created_at_epoch_ms DESC,trigger_order_id DESC", this::decode, args.toArray()).stream().findFirst();
        } catch (DataAccessException unavailable) { throw unavailable(unavailable); }
    }

    public record Page(List<CoreTriggerOrderStateView> orders, String nextCursor, boolean hasMore, String sort) {}

    public Page search(ProductLine product, Long userId, String instrumentId, CoreTriggerOrderStatus status,
                       Long triggerId, int limit, String cursor, String sort) {
        if (product == null || userId != null && userId <= 0 || triggerId != null && triggerId <= 0 || limit < 1 || limit > 1000)
            throw new IllegalArgumentException("invalid trigger query");
        String ordering = sort == null || sort.isBlank() ? "createdAt.desc" : sort.trim();
        if (!Set.of("createdAt.desc", "createdAt.asc").contains(ordering)) throw new IllegalArgumentException("invalid trigger sort");
        String scope = digest(product.name() + '|' + userId + '|' + instrumentId + '|' + status + '|' + triggerId + '|' + ordering);
        long[] after = cursor == null || cursor.isBlank() ? null : cursor(cursor, scope);
        var sql = new StringBuilder("SELECT * FROM core_trigger_order_projection WHERE product_line=?");
        var args = new ArrayList<Object>(); args.add(product.name());
        if (userId != null) { sql.append(" AND user_id=?"); args.add(userId); }
        if (instrumentId != null) { sql.append(" AND instrument_id=?"); args.add(instrumentId); }
        if (status != null) { sql.append(" AND status=?"); args.add(status.name()); }
        if (triggerId != null) { sql.append(" AND trigger_order_id=?"); args.add(triggerId); }
        boolean ascending = ordering.endsWith("asc");
        if (after != null) {
            sql.append(" AND (created_at_epoch_ms,trigger_order_id) ").append(ascending ? ">" : "<").append(" (?,?)");
            args.add(after[0]); args.add(after[1]);
        }
        String direction = ascending ? " ASC" : " DESC";
        sql.append(" ORDER BY created_at_epoch_ms").append(direction).append(",trigger_order_id").append(direction).append(" LIMIT ?");
        args.add(limit + 1);
        try {
            var values = readAssociated(product, sql.toString(), "created_at_epoch_ms" + direction + ",trigger_order_id" + direction, this::decode, args.toArray());
            boolean more = values.size() > limit;
            var page = List.copyOf(more ? values.subList(0, limit) : values);
            var last = page.isEmpty() ? null : page.getLast();
            String next = !more ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("trigger-v1:" + scope + ':' + last.createdAtEpochMillis() + ':' + last.triggerOrderId()).getBytes(StandardCharsets.UTF_8));
            return new Page(page, next, more, ordering);
        } catch (DataAccessException unavailable) { throw unavailable(unavailable); }
    }

    private CoreTriggerOrderStateView decode(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        try {
            var values = CoreTriggerOrderCodec.decodeList(rs.getBytes("raw_trigger_state"));
            if (values.size() != 1) throw new IllegalStateException("invalid trigger row size");
            var v = values.getFirst();
            if (!v.productLine().name().equals(rs.getString("product_line")) || v.triggerOrderId() != rs.getLong("trigger_order_id")
                    || v.userId() != rs.getLong("user_id") || !v.instrumentId().equals(rs.getString("instrument_id"))
                    || !v.clientTriggerOrderId().equals(rs.getString("client_trigger_order_id")) || !v.status().name().equals(rs.getString("status"))
                    || v.revision() != rs.getLong("revision") || v.createdAtEpochMillis() != rs.getLong("created_at_epoch_ms")
                    || v.updatedAtEpochMillis() != rs.getLong("updated_at_epoch_ms"))
                throw new IllegalStateException("trigger row identity mismatch");
            return v;
        } catch (RuntimeException invalid) { throw new ReadViewUnavailableException("TRIGGER_READ_VIEW_INVALID", invalid); }
    }

    private static long[] cursor(String cursor, String scope) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 4 || !parts[0].equals("trigger-v1") || !parts[1].equals(scope)) throw new IllegalArgumentException();
            long time = Long.parseLong(parts[2]), id = Long.parseLong(parts[3]);
            if (time <= 0 || id <= 0) throw new IllegalArgumentException();
            return new long[]{time, id};
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("trigger cursor does not match query", invalid); }
    }

    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ReadViewUnavailableException unavailable(Exception cause) {
        return new ReadViewUnavailableException("TRIGGER_READ_VIEW_UNAVAILABLE", cause);
    }

    /** 必要关联只读：core_trigger_projection_initialization按同产品确认本投影已完整初始化。
     * 标记与数据必须由同一SQL快照读取，避免重建时把旧READY与新空数据混合；
     * LEFT JOIN保留未就绪/已就绪但无数据的区别。仅关联完成性标记，不聚合业务或写其他表。 */
    private <T> List<T> readAssociated(ProductLine product, String sql, String orderBy,
            org.springframework.jdbc.core.RowMapper<T> mapper, Object... values) {
        String associated = """
                SELECT initialized.__projection_ready, projection_rows.*
                  FROM (SELECT count(*)=1 AS __projection_ready FROM core_trigger_projection_initialization WHERE product_line=?) initialized
                  LEFT JOIN LATERAL (%s) projection_rows ON initialized.__projection_ready
                 ORDER BY %s
                """.formatted(sql, orderBy);
        Object[] arguments = new Object[values.length + 1];
        arguments[0] = product.name();
        System.arraycopy(values, 0, arguments, 1, values.length);
        return jdbc.query(associated, (rs, row) -> {
            if (!rs.getBoolean("__projection_ready")) throw new ReadViewUnavailableException("TRIGGER_READ_VIEW_INITIALIZING");
            if (rs.getObject("trigger_order_id") == null) return null;
            return mapper.mapRow(rs, row);
        }, arguments).stream().filter(Objects::nonNull).toList();
    }
}
