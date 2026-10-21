package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.OrderBusinessSettings;
import com.surprising.trading.order.model.OrderSettingsSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** 仅访问 order_business_settings；所有查询与 CAS 更新均限定产品线。 */
@Repository
public class OrderBusinessSettingsRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public OrderBusinessSettingsRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void initialize(ProductLine line, OrderBusinessSettings initial) {
        jdbc.update("""
                INSERT INTO order_business_settings(product_line,settings,version,updated_by,reason)
                VALUES (?,?::jsonb,1,'SYSTEM','initial order settings') ON CONFLICT DO NOTHING
                """, line.name(), json.writeValueAsString(initial));
    }

    public OrderSettingsSnapshot find(ProductLine line) {
        return jdbc.queryForObject("SELECT * FROM order_business_settings WHERE product_line=?",
                (rs, n) -> decode(rs), line.name());
    }

    public Optional<OrderSettingsSnapshot> save(ProductLine line, OrderBusinessSettings settings, long expectedVersion,
                                              String admin, String reason) {
        return jdbc.query("""
                UPDATE order_business_settings SET settings=?::jsonb,version=version+1,updated_by=?,reason=?,updated_at=now()
                WHERE product_line=? AND version=? RETURNING *
                """, (rs, n) -> decode(rs), json.writeValueAsString(settings), admin, reason, line.name(), expectedVersion)
                .stream().findFirst();
    }

    private OrderSettingsSnapshot decode(ResultSet rs) throws SQLException {
        return new OrderSettingsSnapshot(rs.getLong("version"), json.readValue(rs.getString("settings"), OrderBusinessSettings.class),
                rs.getString("updated_by"), rs.getString("reason"), rs.getTimestamp("updated_at").toInstant());
    }
}
