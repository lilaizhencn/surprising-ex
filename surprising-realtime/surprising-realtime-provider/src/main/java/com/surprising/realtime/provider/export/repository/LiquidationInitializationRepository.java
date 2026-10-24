package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅保存各产品强平历史已重建的事实；位置由独立回放检查点验证，不重复保存。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class LiquidationInitializationRepository {
    private final JdbcTemplate jdbc;

    public LiquidationInitializationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean initialized(ProductLine product) {
        return Integer.valueOf(1).equals(jdbc.queryForObject(
                "SELECT count(*) FROM core_liquidation_projection_initialization WHERE product_line=?",
                Integer.class, product.name()));
    }

    public void initialize(ProductLine product) {
        jdbc.update("INSERT INTO core_liquidation_projection_initialization(product_line) VALUES (?) ON CONFLICT DO NOTHING",
                product.name());
    }
}
