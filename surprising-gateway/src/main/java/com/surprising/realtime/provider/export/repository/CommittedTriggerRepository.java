package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;
import java.util.Collection;
import java.util.Collections;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.realtime.provider.export.model.CommittedProjectionRows.TriggerWrite;
import com.surprising.aeron.protocol.*;

/** 仅访问core_trigger_order_projection，保留版本CAS和同版本原始字节校验。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedTriggerRepository {
    private final JdbcTemplate jdbc;

    public CommittedTriggerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void persistTriggers(ProductLine product, Collection<TriggerWrite> writes, long sequence) {
        if (writes.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO core_trigger_order_projection(product_line,trigger_order_id,user_id,
                  client_trigger_order_id,instrument_id,status,created_at_epoch_ms,updated_at_epoch_ms,
                  revision,export_sequence,raw_trigger_state) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (product_line,trigger_order_id) DO UPDATE SET
                  status=EXCLUDED.status,updated_at_epoch_ms=EXCLUDED.updated_at_epoch_ms,
                  revision=EXCLUDED.revision,export_sequence=EXCLUDED.export_sequence,raw_trigger_state=EXCLUDED.raw_trigger_state
                WHERE core_trigger_order_projection.revision < EXCLUDED.revision
                """, writes, 512, (s, write) -> {
            var v = write.state();
            if (v.productLine() != product || v.revision() <= 0 || v.createdAtEpochMillis() <= 0 || v.updatedAtEpochMillis() <= 0)
                throw new IllegalArgumentException("invalid committed trigger identity");
            s.setString(1, product.name()); s.setLong(2, v.triggerOrderId()); s.setLong(3, v.userId());
            s.setString(4, v.clientTriggerOrderId()); s.setString(5, v.instrumentId()); s.setString(6, v.status().name());
            s.setLong(7, v.createdAtEpochMillis()); s.setLong(8, v.updatedAtEpochMillis()); s.setLong(9, v.revision());
            s.setLong(10, sequence); s.setBytes(11, write.raw());
        });
        var arguments = new ArrayList<Object>(); arguments.add(product.name());
        var expected = new HashMap<Long, TriggerWrite>();
        for (var write : writes) { expected.put(write.state().triggerOrderId(), write); arguments.add(write.state().triggerOrderId()); }
        jdbc.query("SELECT * FROM core_trigger_order_projection WHERE product_line=? AND trigger_order_id IN ("
                + String.join(",", Collections.nCopies(writes.size(), "?")) + ") ORDER BY trigger_order_id FOR UPDATE", rs -> {
            var incoming = expected.remove(rs.getLong("trigger_order_id"));
            var v = incoming.state();
            if (rs.getLong("user_id") != v.userId() || !rs.getString("instrument_id").equals(v.instrumentId())
                    || !rs.getString("client_trigger_order_id").equals(v.clientTriggerOrderId())
                    || rs.getLong("created_at_epoch_ms") != v.createdAtEpochMillis()
                    || rs.getLong("revision") < v.revision()
                    || rs.getLong("revision") == v.revision() && !Arrays.equals(rs.getBytes("raw_trigger_state"), incoming.raw()))
                throw new IllegalStateException("conflicting committed trigger state");
        }, arguments.toArray());
        if (!expected.isEmpty()) throw new IllegalStateException("incomplete committed trigger states");
    }
}
