package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.aeron.service.orchestration.replay.CommittedPortfolioSnapshot;

/** 仅访问core_portfolio_cutoff；截止元数据与权益写入由Service事务聚合，删除保留原外键级联。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class PortfolioCutoffRepository {
    private final JdbcTemplate jdbc;

    public PortfolioCutoffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean contains(ProductLine product, long cutoff) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM core_portfolio_cutoff WHERE product_line=? AND cutoff_epoch_ms=?)
                """, Boolean.class, product.name(), cutoff));
    }
    public int insert(CommittedPortfolioSnapshot snapshot, long position, long stateSequence, long flowCoveredFrom, boolean opening) {
        return jdbc.update("""
                INSERT INTO core_portfolio_cutoff VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (product_line,cutoff_epoch_ms) DO NOTHING
                """, snapshot.productLine().name(), snapshot.asOfEpochMillis(), position, stateSequence,
                snapshot.sourceTimestamp(), flowCoveredFrom, opening);

    }
    public void markOpening(ProductLine product, long cutoff) {
        jdbc.update("UPDATE core_portfolio_cutoff SET day_opening=true WHERE product_line=? AND cutoff_epoch_ms=?",product.name(),cutoff);
    }
    public void prune(ProductLine product, long opening, long minute) {
        jdbc.update("""
                DELETE FROM core_portfolio_cutoff WHERE product_line=?
                  AND ((NOT day_opening AND cutoff_epoch_ms<?) OR cutoff_epoch_ms<?)
                """, product.name(), minute - 120_000, opening - 86_400_000);

    }
}
