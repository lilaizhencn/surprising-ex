package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;
import java.util.Collection;
import java.util.Arrays;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreAlgoOrderCodec;

/** 仅访问core_algo_order_projection；按导出序列和版本条件更新并校验不可变身份。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedAlgoRepository {
    private final JdbcTemplate jdbc;

    public CommittedAlgoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void persist(ProductLine product, Collection<CoreAlgoOrderView> values, long sequence) {
        for (var value : values) {
            byte[] raw = CoreAlgoOrderCodec.encode(value);
            jdbc.update("""
                    INSERT INTO core_algo_order_projection(product_line,algo_order_id,user_id,client_algo_order_id,
                      instrument_id,status_code,created_at_epoch_ms,updated_at_epoch_ms,revision,export_sequence,raw_algo_state)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (product_line,algo_order_id) DO UPDATE SET
                      status_code=EXCLUDED.status_code,updated_at_epoch_ms=EXCLUDED.updated_at_epoch_ms,
                      revision=EXCLUDED.revision,export_sequence=EXCLUDED.export_sequence,raw_algo_state=EXCLUDED.raw_algo_state
                    WHERE core_algo_order_projection.export_sequence < EXCLUDED.export_sequence
                      AND core_algo_order_projection.revision <= EXCLUDED.revision
                      AND core_algo_order_projection.user_id=EXCLUDED.user_id
                      AND core_algo_order_projection.instrument_id=EXCLUDED.instrument_id
                      AND core_algo_order_projection.client_algo_order_id=EXCLUDED.client_algo_order_id
                    """, product.name(), value.algoOrderId(), value.userId(), value.clientAlgoOrderId(), value.instrumentId(),
                    value.statusCode(), value.createdAtEpochMillis(), value.updatedAtEpochMillis(), value.revision(), sequence, raw);
            jdbc.query("SELECT * FROM core_algo_order_projection WHERE product_line=? AND algo_order_id=? FOR UPDATE", rs -> {
                if (rs.getLong("user_id") != value.userId() || !rs.getString("instrument_id").equals(value.instrumentId())
                        || !rs.getString("client_algo_order_id").equals(value.clientAlgoOrderId())
                        || rs.getLong("export_sequence") < sequence
                        || rs.getLong("export_sequence") == sequence && !Arrays.equals(raw, rs.getBytes("raw_algo_state")))
                    throw new IllegalStateException("conflicting committed algo state");
            }, product.name(), value.algoOrderId());
        }
    }
}
