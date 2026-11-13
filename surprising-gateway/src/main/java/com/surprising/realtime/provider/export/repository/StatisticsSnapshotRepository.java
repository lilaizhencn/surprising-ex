package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅访问core_trading_statistics_snapshot；同一事务发布四窗口的同一提交前缀。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class StatisticsSnapshotRepository {
    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

    public StatisticsSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void publish(ProductLine product, int window, long position, long now, Map<String,Object> data) {
        jdbc.update("""
                INSERT INTO core_trading_statistics_snapshot VALUES (?,?,?,?,?::jsonb)
                ON CONFLICT (product_line,window_minutes) DO UPDATE SET cluster_position=EXCLUDED.cluster_position,
                  generated_at_epoch_ms=EXCLUDED.generated_at_epoch_ms,metrics=EXCLUDED.metrics
                WHERE core_trading_statistics_snapshot.cluster_position<=EXCLUDED.cluster_position
                """,product.name(),window,position,now,json.writeValueAsString(data));
    }
}
