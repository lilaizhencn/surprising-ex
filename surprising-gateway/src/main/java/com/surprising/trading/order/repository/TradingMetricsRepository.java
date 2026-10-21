package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import com.surprising.trading.order.model.StoredTradingMetrics;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只读取core_trading_statistics_snapshot表的原始快照行；业务校验、榜单和HTTP参数不进入Repository。 */
@Repository
public final class TradingMetricsRepository {
    private final JdbcTemplate jdbc;
    public TradingMetricsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<StoredTradingMetrics> find(ProductLine product, int windowMinutes) {
        try {
            return jdbc.query("SELECT cluster_position,generated_at_epoch_ms,metrics::text FROM core_trading_statistics_snapshot WHERE product_line=? AND window_minutes=?",
                    (rs, row) -> new StoredTradingMetrics(rs.getLong(1), rs.getLong(2), rs.getString(3)), product.name(), windowMinutes)
                    .stream().findFirst();
        } catch (DataAccessException unavailable) {
            throw new ReadViewUnavailableException("TRADING_METRICS_UNAVAILABLE", unavailable);
        }
    }
}
