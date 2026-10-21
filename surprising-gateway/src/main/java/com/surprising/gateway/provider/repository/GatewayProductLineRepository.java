package com.surprising.gateway.provider.repository;

import com.surprising.gateway.provider.model.GatewayProductSetting;
import com.surprising.product.api.ProductLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅访问 gateway_product_lines；版本比较与更新由同一条 SQL 完成。 */
@Repository
public class GatewayProductLineRepository {
    private final JdbcTemplate jdbc;

    public GatewayProductLineRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<GatewayProductSetting> list() {
        return jdbc.query("SELECT * FROM gateway_product_lines ORDER BY product_line", (rs, n) -> decode(rs));
    }

    public Optional<GatewayProductSetting> enable(ProductLine product, long expectedVersion, String admin, String reason) {
        return jdbc.query("""
                UPDATE gateway_product_lines SET enabled=true,version=version+1,updated_by=?,reason=?,updated_at=now()
                WHERE product_line=? AND version=? RETURNING *
                """, (rs, n) -> decode(rs), admin, reason, product.name(), expectedVersion).stream().findFirst();
    }

    private GatewayProductSetting decode(ResultSet rs) throws SQLException {
        return new GatewayProductSetting(ProductLine.valueOf(rs.getString("product_line")), rs.getBoolean("enabled"),
                rs.getLong("version"), rs.getString("updated_by"), rs.getString("reason"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
