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
public class AlgoOrderProjectionRepository {
    private final JdbcTemplate jdbc;
    public AlgoOrderProjectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<CoreAlgoOrderView> openOrders(ProductLine product, long userId, String instrumentId, int limit) {
        var args = new ArrayList<Object>(List.of(product.name(), userId));
        var sql = new StringBuilder("SELECT * FROM trading_algo_orders WHERE product_line=? AND user_id=? AND status_code IN (0,1,2)");
        if (instrumentId != null) {
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
        return one(product, userId, "algo_order_id", id);
    }

    public CoreAlgoOrderView byClient(ProductLine product, long userId, String key) {
        return one(product, userId, "client_algo_order_id", key);
    }

    private CoreAlgoOrderView one(ProductLine product, long userId, String column, Object identity) {
        try {
            var rows = readAssociated(product, "SELECT * FROM trading_algo_orders WHERE product_line=? AND user_id=? AND "
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

    private <T> List<T> readAssociated(ProductLine product, String sql, String orderBy,
            org.springframework.jdbc.core.RowMapper<T> mapper, Object... values) {
        return jdbc.query(sql + " ORDER BY " + orderBy, mapper, values);
    }
}
