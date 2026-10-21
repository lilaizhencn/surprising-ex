package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.aeron.service.orchestration.replay.CommittedPortfolioSnapshot;

/** 仅访问core_portfolio_equity；同一截止时刻的所有用户权益由Service事务统一发布。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class PortfolioEquityRepository {
    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

    public PortfolioEquityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void persist(CommittedPortfolioSnapshot snapshot) {
        jdbc.batchUpdate("""
                INSERT INTO core_portfolio_equity VALUES (?,?,?,?,?::jsonb)
                """, snapshot.users().entrySet(), 256, (s, entry) -> {
            s.setString(1, snapshot.productLine().name()); s.setLong(2, snapshot.asOfEpochMillis());
            s.setLong(3, entry.getKey()); s.setBoolean(4, entry.getValue().complete());
            s.setString(5, json.writeValueAsString(entry.getValue().netAssetUnits()));
        });
    }
}
