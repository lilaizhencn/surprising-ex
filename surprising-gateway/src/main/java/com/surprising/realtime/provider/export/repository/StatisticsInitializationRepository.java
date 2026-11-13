package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅访问core_trading_statistics_initialization；初始化完成标记由Service在相应数据提交后写入。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class StatisticsInitializationRepository {
    private final JdbcTemplate jdbc;

    public StatisticsInitializationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean initialized(ProductLine product) {
        return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT count(*) FROM core_trading_statistics_initialization WHERE product_line=?", Integer.class, product.name()));
    }
    public void initialize(ProductLine product, long position) {
        jdbc.update("INSERT INTO core_trading_statistics_initialization(product_line,snapshot_log_position) VALUES (?,?) ON CONFLICT DO NOTHING",
                product.name(), position);
    }
}
