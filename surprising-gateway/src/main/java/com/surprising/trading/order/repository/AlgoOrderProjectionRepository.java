package com.surprising.trading.order.repository;

import com.surprising.aeron.protocol.CoreAlgoOrderCodec;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.product.api.ProductLine;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

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
            com.surprising.product.api.InstrumentIds.parse(instrumentId);
            sql.append(" AND instrument_id=?"); args.add(instrumentId);
        }
        sql.append(" ORDER BY created_at_epoch_ms DESC,algo_order_id DESC LIMIT ?"); args.add(limit);
        try {
            if (!Integer.valueOf(1).equals(jdbc.queryForObject(
                    "SELECT count(*) FROM core_algo_projection_initialization WHERE product_line=?", Integer.class, product.name())))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ALGO_READ_VIEW_INITIALIZING");
            return jdbc.query(sql.toString(), this::decode, args.toArray());
        } catch (DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ALGO_READ_VIEW_UNAVAILABLE", unavailable);
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
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ALGO_READ_VIEW_INVALID", invalid);
        }
    }
}
