package com.surprising.funding.provider.repository;

import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.provider.config.FundingProperties;
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

    public FundingDueRateRepository(JdbcTemplate jdbcTemplate, FundingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public List<FundingRateResponse> findDue(Instant now, int limit) {
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(now));
        String productCondition = fundingInstrumentCondition(args, "i");
        args.add(limit);
        return jdbcTemplate.query("""
                SELECT DISTINCT ON (r.symbol, r.funding_time) r.*
                  FROM funding_rate_ticks r
                  JOIN instrument_current_versions c
                    ON c.symbol = r.symbol
                  JOIN instruments i
                    ON i.symbol = c.symbol AND i.version = c.version
                 WHERE r.funding_time <= ?
                   AND r.status = 'FINAL'
                   AND %s
                   AND NOT EXISTS (
                       SELECT 1
                         FROM funding_settlements s
                        WHERE s.symbol = r.symbol
                          AND s.funding_time = r.funding_time
                          AND s.status <> 'PROCESSING'
                   )
                 ORDER BY r.symbol, r.funding_time, r.sequence DESC
                 LIMIT ?
                """.formatted(productCondition), (rs, rowNum) -> FundingRateRepository.toRate(rs),
                args.toArray());
    }

    private String fundingInstrumentCondition(List<Object> args, String alias) {
        ProductLine productLine = properties.getKafka().getProductLine();
        if (properties.getKafka().isProductTopicsEnabled()) {
            if (!productLine.isFundingProduct()) {
                return "1 = 0";
            }
            args.add(productLine.contractTypeCode());
            return alias + ".contract_type = ?";
        }
        return alias + ".instrument_type = 'PERPETUAL'";
    }
}
