package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;
import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreAlgoOrderCodec;

/** 仅访问core_algo_order_projection；批量按导出序列与版本 CAS 幂等更新。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommittedAlgoRepository {
    private final JdbcTemplate jdbc;

    public CommittedAlgoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void persist(ProductLine product, Collection<CoreAlgoOrderView> values, long sequence) {
        if (values.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO trading_algo_orders(product_line,algo_order_id,user_id,client_algo_order_id,
                  instrument_id,status_code,created_at_epoch_ms,updated_at_epoch_ms,revision,export_sequence,raw_algo_state)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (product_line,algo_order_id) DO UPDATE SET
                  status_code=EXCLUDED.status_code,updated_at_epoch_ms=EXCLUDED.updated_at_epoch_ms,
                  revision=EXCLUDED.revision,export_sequence=EXCLUDED.export_sequence,raw_algo_state=EXCLUDED.raw_algo_state
                WHERE trading_algo_orders.export_sequence < EXCLUDED.export_sequence
                  AND trading_algo_orders.revision <= EXCLUDED.revision
                  AND trading_algo_orders.user_id=EXCLUDED.user_id
                  AND trading_algo_orders.instrument_id=EXCLUDED.instrument_id
                  AND trading_algo_orders.client_algo_order_id=EXCLUDED.client_algo_order_id
                """, values, 256, (s, value) -> {
            s.setString(1, product.name());
            s.setLong(2, value.algoOrderId());
            s.setLong(3, value.userId());
            s.setString(4, value.clientAlgoOrderId());
            s.setString(5, value.instrumentId());
            s.setInt(6, value.statusCode());
            s.setLong(7, value.createdAtEpochMillis());
            s.setLong(8, value.updatedAtEpochMillis());
            s.setLong(9, value.revision());
            s.setLong(10, sequence);
            s.setBytes(11, CoreAlgoOrderCodec.encode(value));
        });
    }
}
