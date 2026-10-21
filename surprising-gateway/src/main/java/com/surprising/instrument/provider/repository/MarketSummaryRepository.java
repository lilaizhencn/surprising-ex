package com.surprising.instrument.provider.repository;

import com.surprising.instrument.provider.model.MarketSummaryRows.Sample;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 必要关联只读例外：candlestick_candles提供已关闭1m样本；instruments仅认证instrumentId唯一产品归属。
 * 目前K线键不包含产品线，因此跨产品重复ID不能展示摘要。一次SQL批量读取期初真实收盘价与时窗样本，
 * 不逐合约补读；Service定义24小时时窗并计算涨跌幅、成交量及趋势。 */
@Repository
public class MarketSummaryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public MarketSummaryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Sample> samples(List<Integer> instrumentIds, Instant since, Instant until) {
        if (instrumentIds.isEmpty()) return List.of();
        var arguments = new MapSqlParameterSource()
                .addValue("ids", instrumentIds.stream().map(String::valueOf).toList())
                .addValue("since", Timestamp.from(since))
                .addValue("until", Timestamp.from(until));
        return jdbc.query("""
                WITH eligible AS (
                    SELECT i.instrument_id::text AS instrument_id
                      FROM instruments i WHERE i.instrument_id::text IN (:ids)
                     GROUP BY i.instrument_id HAVING count(DISTINCT i.product_line) = 1
                ), samples AS (
                    SELECT e.instrument_id, p.close_price AS open_price, p.close_price AS high_price,
                           p.close_price AS low_price, p.close_price, 0::numeric AS base_volume,
                           0::numeric AS quote_volume, :since::timestamptz AS sample_time, 0 AS sample_order
                      FROM eligible e CROSS JOIN LATERAL (
                          SELECT c.close_price FROM candlestick_candles c
                           WHERE c.instrument_id = e.instrument_id AND c.period = '1m'
                             AND c.status = 'CLOSED' AND c.close_time <= :since
                           ORDER BY c.open_time DESC LIMIT 1
                      ) p
                    UNION ALL
                    SELECT c.instrument_id, c.open_price, c.high_price, c.low_price,
                           c.close_price, c.base_volume, c.quote_volume, c.open_time, 1
                      FROM candlestick_candles c JOIN eligible e USING (instrument_id)
                     WHERE c.period = '1m' AND c.status = 'CLOSED'
                       AND c.open_time >= :since AND c.open_time < :until
                )
                SELECT instrument_id, open_price, high_price, low_price, close_price, base_volume, quote_volume
                  FROM samples ORDER BY instrument_id, sample_time, sample_order
                """, arguments, (rs, index) -> new Sample(
                Integer.parseInt(rs.getString("instrument_id")),
                rs.getBigDecimal("open_price"), rs.getBigDecimal("high_price"),
                rs.getBigDecimal("low_price"), rs.getBigDecimal("close_price"),
                rs.getBigDecimal("base_volume"), rs.getBigDecimal("quote_volume")));
    }
}
