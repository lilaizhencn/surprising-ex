package com.surprising.funding.provider.repository;

import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 兼容旧恢复工具的数据库到期费率查询；资金费生产定时任务不再注入此类。
 */
public class FundingDueRateRepository {

    private final JdbcTemplate jdbcTemplate;
    private final FundingProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public FundingDueRateRepository(JdbcTemplate jdbcTemplate, FundingProperties properties) {
        this(jdbcTemplate, properties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FundingDueRateRepository(JdbcTemplate jdbcTemplate,
                                    FundingProperties properties,
                                    @org.springframework.beans.factory.annotation.Qualifier("fundingInstrumentSnapshotCache")
                                    InstrumentSnapshotCache snapshotCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    public List<FundingRateResponse> findDue(Instant now, int limit) {
        ProductLine productLine = properties.getKafka().isProductTopicsEnabled()
                ? properties.getKafka().getProductLine() : ProductLine.LINEAR_PERPETUAL;
        if (snapshotCache == null || !snapshotCache.initialized(productLine) || !productLine.isFundingProduct()) {
            return List.of();
        }
        List<FundingRateResponse> rows = jdbcTemplate.query("""
                SELECT DISTINCT ON (r.symbol, r.funding_time) r.*
                  FROM funding_rate_ticks r
                 WHERE r.funding_time <= ?
                   AND r.status = 'FINAL'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM funding_settlements s
                        WHERE s.symbol = r.symbol
                          AND s.funding_time = r.funding_time
                          AND s.status <> 'PROCESSING'
                   )
                 ORDER BY r.symbol, r.funding_time, r.sequence DESC
                 LIMIT ?
                """, (rs, rowNum) -> FundingRateRepository.toRate(rs), Timestamp.from(now), limit);
        return rows.stream()
                .filter(rate -> snapshotCache.current(productLine, rate.symbol()).isPresent())
                .limit(Math.max(1, limit))
                .toList();
    }
}
