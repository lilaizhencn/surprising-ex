package com.surprising.candlestick.provider.repository;

import com.surprising.candlestick.api.model.CandleStatus;
import com.surprising.candlestick.api.model.CandlePeriod;
import com.surprising.candlestick.provider.aggregation.CandleSink;
import com.surprising.candlestick.provider.aggregation.CandleSnapshot;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code candlestick_candles} 表的批量写入。
 *
 * <p>处理器只写入首次关闭的完整 1 分钟快照；相同 symbol 和开盘时间的重试不会改写历史。</p>
 */
@Repository
public class PostgresCandleSink implements CandleSink {

    private static final String UPSERT_SQL = """
            INSERT INTO candlestick_candles (
                symbol, period, open_time, close_time,
                open_price, high_price, low_price, close_price,
                base_volume, quote_volume, trade_count,
                first_trade_id, last_trade_id, first_sequence, last_sequence,
                status, updated_at, source_partition, source_offset
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, period, open_time) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgresCandleSink(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 使用一个 JDBC batch 首次持久化关闭的 1 分钟 K 线；冲突行保持不可变。
     */
    @Override
    public void upsertBatch(List<CandleSnapshot> candles) {
        if (candles == null || candles.isEmpty()) {
            return;
        }
        List<CandleSnapshot> closedCandles = candles.stream()
                .filter(candle -> candle != null && candle.getStatus() == CandleStatus.CLOSED
                        && CandlePeriod.M1.code().equals(candle.getPeriod()))
                .toList();
        if (closedCandles.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                CandleSnapshot candle = closedCandles.get(i);
                ps.setString(1, candle.getSymbol());
                ps.setString(2, candle.getPeriod());
                ps.setTimestamp(3, Timestamp.from(candle.getOpenTime()));
                ps.setTimestamp(4, Timestamp.from(candle.getCloseTime()));
                ps.setBigDecimal(5, candle.getOpenPrice());
                ps.setBigDecimal(6, candle.getHighPrice());
                ps.setBigDecimal(7, candle.getLowPrice());
                ps.setBigDecimal(8, candle.getClosePrice());
                ps.setBigDecimal(9, candle.getBaseVolume());
                ps.setBigDecimal(10, candle.getQuoteVolume());
                ps.setLong(11, candle.getTradeCount());
                ps.setString(12, candle.getFirstTradeId());
                ps.setString(13, candle.getLastTradeId());
                setNullableLong(ps, 14, candle.getFirstSequence());
                setNullableLong(ps, 15, candle.getLastSequence());
                ps.setString(16, candle.getStatus().name());
                ps.setTimestamp(17, Timestamp.from(candle.getUpdatedAt()));
                setNullableInt(ps, 18, candle.getSourcePartition());
                setNullableLong(ps, 19, candle.getSourceOffset());
            }

            @Override
            public int getBatchSize() {
                return closedCandles.size();
            }
        });
    }

    private void setNullableLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private void setNullableInt(PreparedStatement ps, int index, Integer value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}
