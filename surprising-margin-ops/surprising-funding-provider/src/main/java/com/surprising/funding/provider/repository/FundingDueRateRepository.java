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
import org.springframework.stereotype.Repository;

/**
 * 查找当前产品线中尚未结算的到期最终费率。
 *
 * <p>不可拆原因：到期选择必须在一个数据库快照中同时验证最终费率、当前合约产品线和结算幂等状态；
 * 拆分后会在合约版本切换或并发创建结算时重复派发。该查询属于在线结算控制，不提供后台时间线、对账或报表。</p>
 */
@Repository
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
