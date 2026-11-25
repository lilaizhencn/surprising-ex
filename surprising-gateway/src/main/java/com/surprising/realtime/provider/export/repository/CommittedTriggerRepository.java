package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;
import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.realtime.provider.export.model.CommittedProjectionRows.TriggerWrite;

/** 仅访问core_trigger_order_projection，按 revision CAS 单句幂等更新条件单状态。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommittedTriggerRepository {
    private final JdbcTemplate jdbc;

    public CommittedTriggerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void persistTriggers(ProductLine product, Collection<TriggerWrite> writes, long sequence) {
        if (writes.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO trading_trigger_orders(product_line,trigger_order_id,user_id,
                  client_trigger_order_id,instrument_id,status,created_at_epoch_ms,updated_at_epoch_ms,
                  revision,export_sequence,raw_trigger_state) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (product_line,trigger_order_id) DO UPDATE SET
                  status=EXCLUDED.status,updated_at_epoch_ms=EXCLUDED.updated_at_epoch_ms,
                  revision=EXCLUDED.revision,export_sequence=EXCLUDED.export_sequence,raw_trigger_state=EXCLUDED.raw_trigger_state
                WHERE trading_trigger_orders.revision < EXCLUDED.revision
                """, writes, 512, (s, write) -> {
            var v = write.state();
            if (v.productLine() != product || v.revision() <= 0 || v.createdAtEpochMillis() <= 0 || v.updatedAtEpochMillis() <= 0)
                throw new IllegalArgumentException("invalid committed trigger identity");
            s.setString(1, product.name()); s.setLong(2, v.triggerOrderId()); s.setLong(3, v.userId());
            s.setString(4, v.clientTriggerOrderId()); s.setString(5, v.instrumentId()); s.setString(6, v.status().name());
            s.setLong(7, v.createdAtEpochMillis()); s.setLong(8, v.updatedAtEpochMillis()); s.setLong(9, v.revision());
            s.setLong(10, sequence); s.setBytes(11, write.raw());
        });
    }
}
