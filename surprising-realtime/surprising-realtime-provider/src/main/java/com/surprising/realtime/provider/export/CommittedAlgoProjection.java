package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.CoreAlgoOrderCodec;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay;
import com.surprising.product.api.ProductLine;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Rebuildable algo read state; invoked only by the committed replay export worker. */
final class CommittedAlgoProjection {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    CommittedAlgoProjection(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    void initialize(ProductLine product, CommittedTradeReplay replay, long checkpointPosition) {
        if (Integer.valueOf(1).equals(jdbc.queryForObject(
                "SELECT count(*) FROM core_algo_projection_initialization WHERE product_line=?", Integer.class, product.name()))) return;
        var page = new ArrayList<CoreAlgoOrderView>(256);
        replay.forEachRetainedAlgo(value -> {
            page.add(value);
            if (page.size() == 256) {
                transaction.executeWithoutResult(tx -> persist(product, page, replay.exportSequence()));
                page.clear();
            }
        });
        transaction.executeWithoutResult(tx -> {
            persist(product, page, replay.exportSequence());
            jdbc.update("INSERT INTO core_algo_projection_initialization(product_line,snapshot_log_position) VALUES (?,?) ON CONFLICT DO NOTHING",
                    product.name(), checkpointPosition);
        });
    }

    /** Caller owns the transaction shared with order changes and the visibility watermark. */
    void persist(ProductLine product, List<CoreAlgoOrderView> changes, long sequence) {
        var latest = new LinkedHashMap<Long, CoreAlgoOrderView>();
        for (var value : changes) {
            if (value.userId() <= 0 || value.algoOrderId() <= 0 || value.revision() <= 0
                    || value.createdAtEpochMillis() <= 0 || value.updatedAtEpochMillis() <= 0
                    || value.statusCode() < 0 || value.statusCode() > 5)
                throw new IllegalArgumentException("invalid committed algo state");
            var previous = latest.put(value.algoOrderId(), value);
            if (previous != null && (previous.userId() != value.userId()
                    || !previous.clientAlgoOrderId().equals(value.clientAlgoOrderId())
                    || !previous.instrumentId().equals(value.instrumentId()) || previous.revision() > value.revision()))
                throw new IllegalStateException("conflicting committed algo batch");
        }
        for (var value : latest.values()) {
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
