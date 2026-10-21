package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cOrderEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** 只访问 c2c_order_events；由订单Service在状态变更的同一事务中追加时间线。 */
@Repository
public class C2cOrderEventRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public C2cOrderEventRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public List<C2cOrderEvent> forOrder(UUID orderId) {
        return jdbc.query("SELECT * FROM c2c_order_events WHERE order_id=? ORDER BY event_id LIMIT 200",
                (r, n) -> new C2cOrderEvent(r.getLong("event_id"), r.getObject("actor_user_id", Long.class),
                        r.getString("event_type"), json.readTree(r.getString("details")),
                        r.getTimestamp("created_at").toInstant()), orderId);
    }

    public void append(UUID orderId, Long actor, String type, Map<String, ?> details) {
        jdbc.update("INSERT INTO c2c_order_events(order_id,actor_user_id,event_type,details) VALUES(?,?,?,?::jsonb)",
                orderId, actor, type, json.writeValueAsString(details));
    }
}
