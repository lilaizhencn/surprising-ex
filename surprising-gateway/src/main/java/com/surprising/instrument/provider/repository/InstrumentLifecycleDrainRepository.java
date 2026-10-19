package com.surprising.instrument.provider.repository;

import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code instrument_lifecycle_drain_acks} 表。 */
@Repository
public class InstrumentLifecycleDrainRepository {

    private final JdbcTemplate jdbcTemplate;

    public InstrumentLifecycleDrainRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void acknowledge(InstrumentLifecycleDrainEvent event, Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO instrument_lifecycle_drain_acks (
                    instrument_id, instrument_change_id, product_line, component, ready_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (product_line, instrument_id, instrument_change_id, component) DO UPDATE SET
                    product_line = EXCLUDED.product_line,
                    ready_at = GREATEST(instrument_lifecycle_drain_acks.ready_at, EXCLUDED.ready_at),
                    updated_at = EXCLUDED.updated_at
                """, com.surprising.product.api.InstrumentIds.parse(event.instrumentId()), event.instrumentChangeId(), event.productLine().name(),
                event.component().name(), Timestamp.from(event.readyAt()), Timestamp.from(now));
        if (rows != 1) {
            throw new IllegalStateException("生命周期清理确认写入失败: "
                    + event.instrumentId() + ":" + event.component());
        }
    }

    public boolean isReady(ProductLine productLine, String instrumentId, long instrumentChangeId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT component)::int
                  FROM instrument_lifecycle_drain_acks
                 WHERE product_line = ?
                   AND instrument_id = ?
                   AND instrument_change_id = ?
                   AND component = 'ORDER'
                """, Integer.class, productLine.name(), com.surprising.product.api.InstrumentIds.parse(instrumentId), instrumentChangeId);
        // 订单终态、预占释放和成交结算已由同一个交易 Core 提交；当前只有 ORDER 发布者。
        // 旧 ACCOUNT 账户 reducer 不再存在，其历史确认不能替代当前版本的 Core 订单确认。
        return count != null && count == 1;
    }
}
