package com.surprising.instrument.provider.repository;

import com.surprising.instrument.api.model.InstrumentLifecycleDrainComponent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code instrument_lifecycle_drain_acks} 表。 */
@Repository
public class InstrumentLifecycleDrainRepository {

    private static final Set<InstrumentLifecycleDrainComponent> REQUIRED_COMPONENTS =
            Set.of(InstrumentLifecycleDrainComponent.ORDER,
                    InstrumentLifecycleDrainComponent.TRIGGER,
                    InstrumentLifecycleDrainComponent.ACCOUNT);

    private final JdbcTemplate jdbcTemplate;

    public InstrumentLifecycleDrainRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void acknowledge(InstrumentLifecycleDrainEvent event, Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO instrument_lifecycle_drain_acks (
                    symbol, instrument_version, product_line, component, ready_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (symbol, instrument_version, component) DO UPDATE SET
                    product_line = EXCLUDED.product_line,
                    ready_at = GREATEST(instrument_lifecycle_drain_acks.ready_at, EXCLUDED.ready_at),
                    updated_at = EXCLUDED.updated_at
                """, event.symbol(), event.instrumentVersion(), event.productLine().name(),
                event.component().name(), Timestamp.from(event.readyAt()), Timestamp.from(now));
        if (rows != 1) {
            throw new IllegalStateException("生命周期清理确认写入失败: "
                    + event.symbol() + ":" + event.component());
        }
    }

    public boolean isReady(ProductLine productLine, String symbol, long instrumentVersion) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT component)::int
                  FROM instrument_lifecycle_drain_acks
                 WHERE product_line = ?
                   AND symbol = ?
                   AND instrument_version = ?
                   AND component IN ('ORDER', 'TRIGGER', 'ACCOUNT')
                """, Integer.class, productLine.name(), symbol, instrumentVersion);
        return count != null && count == REQUIRED_COMPONENTS.size();
    }
}
