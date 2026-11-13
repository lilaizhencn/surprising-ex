package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅访问core_trading_statistics_cursor；Service在相同事务中合并分钟计数并推进提交前缀。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class StatisticsCursorRepository {
    private final JdbcTemplate jdbc;

    public StatisticsCursorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long lock(ProductLine product) {
        jdbc.update("INSERT INTO core_trading_statistics_cursor VALUES (?,0) ON CONFLICT DO NOTHING",product.name());
        return jdbc.queryForObject("SELECT last_command_position FROM core_trading_statistics_cursor WHERE product_line=? FOR UPDATE",Long.class,product.name());
    }
    public List<Long> current(ProductLine product) {
        return jdbc.queryForList("SELECT last_command_position FROM core_trading_statistics_cursor WHERE product_line=?",Long.class,product.name());
    }
    public void advance(ProductLine product, long position) {
        jdbc.update("UPDATE core_trading_statistics_cursor SET last_command_position=? WHERE product_line=?",position,product.name());
    }
}
