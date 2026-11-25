package com.surprising.gateway.provider.repository;

import com.surprising.gateway.provider.model.PortfolioIncomeRows.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 必要关联只读例外：core_execution_projection提供实际现货成交，instruments与assets提供tick和报价单位，
 * core_execution_projection_initialization/core_projection_watermark证明给定截止前缀已覆盖。
 * 产品、时窗和前缀约束在一次SQL中完成；Service传入价格时窗并在同一数据库快照内聚合，不查询在线Core。 */
@Repository
public class PortfolioIncomeSpotPriceRepository {
    private final JdbcTemplate jdbc;

    public PortfolioIncomeSpotPriceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Price> spotPrice(String asset, long earliest, long time, Cut spotCut) {
        return jdbc.query("""
                SELECT e.price_ticks,i.price_tick_units,q.scale_units,e.occurred_at_epoch_ms
                  FROM trading_trades e JOIN instruments i
                    ON i.product_line='SPOT' AND e.product_line='SPOT' AND e.instrument_id=i.instrument_id::text
                  JOIN assets b ON b.asset_id=i.base_asset_id JOIN assets q ON q.asset_id=i.quote_asset_id
                 WHERE b.asset=? AND q.asset='USDT' AND e.price_ticks>0 AND i.price_tick_units>0 AND q.scale_units>0
                   AND e.occurred_at_epoch_ms>=? AND e.occurred_at_epoch_ms<=? AND e.export_sequence<=?
                   AND EXISTS(SELECT 1 FROM projection_watermarks w
                     WHERE w.product_line='SPOT' AND w.last_export_sequence>=?)
                 ORDER BY e.occurred_at_epoch_ms DESC,e.export_sequence DESC,e.execution_index DESC LIMIT 1
                """, (rs, row) -> new Price(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                asset, earliest, time, spotCut.position(),
                spotCut.stateSequence()).stream().findFirst();
    }
}
