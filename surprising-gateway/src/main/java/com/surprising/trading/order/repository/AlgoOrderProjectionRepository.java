package com.surprising.trading.order.repository;

import com.surprising.aeron.protocol.CoreAlgoOrderCodec;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.product.api.ProductLine;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;

/** User algo reads use only the latest state produced by realtime committed Archive replay. */
@Repository
public final class AlgoOrderProjectionRepository {
    private final JdbcTemplate jdbc;
    public AlgoOrderProjectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<CoreAlgoOrderView> openOrders(ProductLine product, long userId, String instrumentId, int limit) {
        if (product == null || userId <= 0 || limit < 1 || limit > 1000)
            throw new IllegalArgumentException("invalid algo query");
        var args = new ArrayList<Object>(List.of(product.name(), userId));
        var sql = new StringBuilder("SELECT * FROM core_algo_order_projection WHERE product_line=? AND user_id=? AND status_code IN (0,1,2)");
        if (instrumentId != null && !instrumentId.isBlank()) {
            com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
            sql.append(" AND instrument_id=?"); args.add(instrumentId);
        }
        sql.append(" ORDER BY created_at_epoch_ms DESC,algo_order_id DESC LIMIT ?"); args.add(limit);
        try {
            return readAssociated(product, sql.toString(), "created_at_epoch_ms DESC,algo_order_id DESC", this::decode, args.toArray());
        } catch (DataAccessException unavailable) {
            throw new ReadViewUnavailableException("ALGO_READ_VIEW_UNAVAILABLE", unavailable);
        }
    }

    public CoreAlgoOrderView byId(ProductLine product, long userId, long id) {
        if (id <= 0) throw new IllegalArgumentException("algoOrderId must be positive");
        return one(product, userId, "algo_order_id", id);
    }

    public CoreAlgoOrderView byClient(ProductLine product, long userId, String key) {
        if (key == null || key.isBlank() || key.length() > 64)
            throw new IllegalArgumentException("clientAlgoOrderId is required and must be at most 64 characters");
        return one(product, userId, "client_algo_order_id", key);
    }

    private CoreAlgoOrderView one(ProductLine product, long userId, String column, Object identity) {
        if (product == null || userId <= 0) throw new IllegalArgumentException("invalid algo owner");
        try {
            var rows = readAssociated(product, "SELECT * FROM core_algo_order_projection WHERE product_line=? AND user_id=? AND "
                    + column + "=?", "created_at_epoch_ms DESC,algo_order_id DESC", this::decode, product.name(), userId, identity);
            return rows.isEmpty() ? null : rows.getFirst();
        } catch (DataAccessException unavailable) {
            throw new ReadViewUnavailableException("ALGO_READ_VIEW_UNAVAILABLE", unavailable);
        }
    }

    private CoreAlgoOrderView decode(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        try {
            var value = CoreAlgoOrderCodec.decode(rs.getBytes("raw_algo_state"));
            if (value.algoOrderId() != rs.getLong("algo_order_id") || value.userId() != rs.getLong("user_id")
                    || !value.instrumentId().equals(rs.getString("instrument_id"))
                    || !value.clientAlgoOrderId().equals(rs.getString("client_algo_order_id"))
                    || value.statusCode() != rs.getInt("status_code") || value.revision() != rs.getLong("revision")
                    || value.createdAtEpochMillis() != rs.getLong("created_at_epoch_ms")
                    || value.updatedAtEpochMillis() != rs.getLong("updated_at_epoch_ms")
                    || value.executedQuantitySteps() < 0 || value.activeQuantitySteps() < 0
                    || Math.addExact(value.executedQuantitySteps(),value.activeQuantitySteps()) > value.quantitySteps())
                throw new IllegalStateException("algo read view identity or progress mismatch");
            return value;
        } catch (RuntimeException invalid) {
            throw new ReadViewUnavailableException("ALGO_READ_VIEW_INVALID", invalid);
        }
    }

    /** 必要关联只读：core_algo_projection_initialization按同产品确认本投影已完整初始化。
     * 标记与数据必须由同一SQL快照读取，避免重建时把旧READY与新空数据混合；
     * LEFT JOIN保留未就绪/已就绪但无数据的区别。仅关联完成性标记，不聚合业务或写其他表。 */
    private <T> List<T> readAssociated(ProductLine product, String sql, String orderBy,
            org.springframework.jdbc.core.RowMapper<T> mapper, Object... values) {
        String associated = """
                SELECT initialized.__projection_ready, projection_rows.*
                  FROM (SELECT count(*)=1 AS __projection_ready FROM core_algo_projection_initialization WHERE product_line=?) initialized
                  LEFT JOIN LATERAL (%s) projection_rows ON initialized.__projection_ready
                 ORDER BY %s
                """.formatted(sql, orderBy);
        Object[] arguments = new Object[values.length + 1];
        arguments[0] = product.name();
        System.arraycopy(values, 0, arguments, 1, values.length);
        return jdbc.query(associated, (rs, row) -> {
            if (!rs.getBoolean("__projection_ready")) throw new ReadViewUnavailableException("ALGO_READ_VIEW_INITIALIZING");
            if (rs.getObject("algo_order_id") == null) return null;
            return mapper.mapRow(rs, row);
        }, arguments).stream().filter(Objects::nonNull).toList();
    }
}
