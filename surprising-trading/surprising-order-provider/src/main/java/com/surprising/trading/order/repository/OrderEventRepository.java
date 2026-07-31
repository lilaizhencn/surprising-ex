package com.surprising.trading.order.repository;

import com.surprising.trading.api.model.OrderEvent;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 订单事件仓储，只负责 {@code trading_order_events} 表。
 */
@Repository
public class OrderEventRepository {

    private static final String INSERT_SQL = """
            INSERT INTO trading_order_events (
                event_id, order_id, user_id, symbol, event_type, status, reason, trace_id, event_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public OrderEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(OrderEvent event) {
        int rows = jdbcTemplate.update(INSERT_SQL, event.eventId(), event.orderId(), event.userId(), event.symbol(),
                event.eventType().name(), event.status().name(), event.reason(), event.traceId(),
                Timestamp.from(event.eventTime()));
        if (rows != 1) {
            throw new IllegalStateException("写入订单事件失败：" + event.eventId());
        }
    }

    public void insertAll(List<OrderEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        int[] rows = jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                OrderEvent event = events.get(index);
                statement.setLong(1, event.eventId());
                statement.setLong(2, event.orderId());
                statement.setLong(3, event.userId());
                statement.setString(4, event.symbol());
                statement.setString(5, event.eventType().name());
                statement.setString(6, event.status().name());
                statement.setString(7, event.reason());
                statement.setString(8, event.traceId());
                statement.setTimestamp(9, Timestamp.from(event.eventTime()));
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("批量写入订单事件失败");
            }
        }
    }
}
