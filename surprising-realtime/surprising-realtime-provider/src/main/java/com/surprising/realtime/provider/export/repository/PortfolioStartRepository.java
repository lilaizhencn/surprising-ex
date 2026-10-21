package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.realtime.provider.export.model.PortfolioProjectionStart;

/** 仅访问core_portfolio_projection_start；保留首次覆盖边界和从创世重建完成标记。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class PortfolioStartRepository {
    private final JdbcTemplate jdbc;

    public PortfolioStartRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PortfolioProjectionStart start(ProductLine product, long checkpointPosition, long checkpointTimestamp) {
        jdbc.update("""
                INSERT INTO core_portfolio_projection_start(product_line,cluster_position,occurred_at_epoch_ms) VALUES (?,?,?)
                ON CONFLICT (product_line) DO NOTHING
                """, product.name(), checkpointPosition, checkpointTimestamp);
        return jdbc.queryForObject("""
                SELECT cluster_position,occurred_at_epoch_ms FROM core_portfolio_projection_start WHERE product_line=?
                """, (rs, row) -> new PortfolioProjectionStart(rs.getLong(1), rs.getLong(2)), product.name());
    }

    public boolean initialized(ProductLine product) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM core_portfolio_projection_start
                  WHERE product_line=? AND cluster_position=0 AND occurred_at_epoch_ms=0 AND ready)
                """, Boolean.class, product.name()));
    }

    public void certifyGenesis(ProductLine product) {
        jdbc.update("""
                INSERT INTO core_portfolio_projection_start VALUES (?,0,0,true)
                ON CONFLICT (product_line) DO UPDATE SET cluster_position=0,occurred_at_epoch_ms=0,ready=true
                """, product.name());
    }
}
