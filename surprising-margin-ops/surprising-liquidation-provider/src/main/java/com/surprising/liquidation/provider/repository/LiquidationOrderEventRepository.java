package com.surprising.liquidation.provider.repository;

import com.surprising.trading.api.model.OrderEvent;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 强平订单事件仓储，只负责 {@code trading_order_events} 表。 */
@Repository
public class LiquidationOrderEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public LiquidationOrderEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertAll(List<OrderEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO trading_order_events (
                    event_id, order_id, user_id, symbol, event_type, status, reason, event_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, events.stream().map(event -> new Object[]{
                event.eventId(), event.orderId(), event.userId(), event.symbol(), event.eventType().name(),
                event.status().name(), event.reason(), Timestamp.from(event.eventTime())
        }).toList());
        requireBatchRows(rows, events.size());
    }

    public void insert(OrderEvent event) {
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_order_events (
                    event_id, order_id, user_id, symbol, event_type, status, reason, event_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.orderId(), event.userId(), event.symbol(), event.eventType().name(),
                event.status().name(), event.reason(), Timestamp.from(event.eventTime()));
        if (rows != 1) {
            throw new IllegalStateException("写入强平订单事件失败");
        }
    }

    private void requireBatchRows(int[] rows, int expected) {
        if (rows.length != expected) {
            throw new IllegalStateException("批量写入强平订单事件结果数量不一致");
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("批量写入强平订单事件失败");
            }
        }
    }
}
