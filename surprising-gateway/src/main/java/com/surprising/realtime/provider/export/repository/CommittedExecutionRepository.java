package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.realtime.provider.export.model.CommittedProjectionRows.ExecutionWrite;

/** 仅访问core_execution_projection；幂等写入已提交成交事实，不阻塞行情导出。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommittedExecutionRepository {
    private final JdbcTemplate jdbc;

    public CommittedExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void persistExecutions(ProductLine product, List<ExecutionWrite> writes) {
        if (writes.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO trading_trades(product_line,trade_id,export_sequence,execution_index,
                  taker_order_id,maker_order_id,taker_user_id,maker_user_id,instrument_id,taker_side,
                  taker_position_side,maker_position_side,taker_fee_rate_ppm,maker_fee_rate_ppm,price_ticks,quantity_steps,occurred_at_epoch_ms)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """, writes, 512, (s, v) -> {
            s.setString(1, product.name()); s.setString(2, v.tradeId()); s.setLong(3, v.position()); s.setInt(4, v.index());
            s.setLong(5, v.takerOrder()); s.setLong(6, v.makerOrder()); s.setLong(7, v.takerUser()); s.setLong(8, v.makerUser());
            s.setString(9, v.instrument()); s.setString(10, v.side()); s.setString(11, v.takerPositionSide()); s.setString(12, v.makerPositionSide());
            s.setLong(13, v.takerFeeRate()); s.setLong(14, v.makerFeeRate()); s.setLong(15, v.price()); s.setLong(16, v.quantity()); s.setLong(17, v.timestamp());
        });
    }

    public List<Map<String,Object>> symbolTrades(ProductLine product, long from, long position) {
        return jdbc.queryForList("""
                SELECT instrument_id AS "instrumentId",count(*) AS trades,max(occurred_at_epoch_ms) AS "lastTradeEpochMs"
                FROM trading_trades WHERE product_line=? AND occurred_at_epoch_ms>=? AND export_sequence<=? GROUP BY instrument_id
                """,product.name(),from,position);
    }

    public long participants(ProductLine product, long from, long position) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT taker_user_id FROM trading_trades WHERE product_line=? AND occurred_at_epoch_ms>=? AND export_sequence<=?
                UNION SELECT maker_user_id FROM trading_trades WHERE product_line=? AND occurred_at_epoch_ms>=? AND export_sequence<=?) participants
                """,Long.class,product.name(),from,position,product.name(),from,position);
    }

    public List<Map<String,Object>> lastPrices(ProductLine product, long from, long position) {
        return jdbc.queryForList("""
                SELECT DISTINCT ON (instrument_id) instrument_id,price_ticks FROM trading_trades
                WHERE product_line=? AND occurred_at_epoch_ms>=? AND export_sequence<=?
                ORDER BY instrument_id,occurred_at_epoch_ms DESC,export_sequence DESC,execution_index DESC
                """,product.name(),from,position);
    }
}
