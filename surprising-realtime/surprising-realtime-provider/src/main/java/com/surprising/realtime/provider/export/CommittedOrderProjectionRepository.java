package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Read model only: the committed Archive replay remains the authority for order state. */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedOrderProjectionRepository {
    private static final String UPSERT = """
            INSERT INTO core_order_projection
              (product_line,order_id,user_id,client_order_id,instrument_id,status,
               created_at_epoch_ms,updated_at_epoch_ms,cluster_position,order_revision,
               export_sequence,raw_order_state)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (product_line,order_id) DO UPDATE SET
              client_order_id=EXCLUDED.client_order_id,status=EXCLUDED.status,
              updated_at_epoch_ms=EXCLUDED.updated_at_epoch_ms,
              cluster_position=EXCLUDED.cluster_position,order_revision=EXCLUDED.order_revision,
              export_sequence=EXCLUDED.export_sequence,raw_order_state=EXCLUDED.raw_order_state
            WHERE core_order_projection.order_revision < EXCLUDED.order_revision
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public CommittedOrderProjectionRepository(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** All orders and their visibility watermark commit together, before the export checkpoint. */
    void persist(ProductLine product, List<RealtimeFrame> orders, long exportSequence) {
        if (product == null || exportSequence < 0) throw new IllegalArgumentException("invalid order projection batch");
        // Read each committed batch from the admin catalog. Disabled strategies still own
        // their historical accounts; changing enablement must not reclassify old maker fills.
        Set<Long> marketMakerAccounts = new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT unnest(account_ids) FROM market_maker_strategies WHERE product_line=?",
                Long.class, product.name()));
        // This bounded, per-commit SQL batch owns only the latest supplied version of each order.
        // PostgreSQL multi-row ON CONFLICT cannot update the same key twice in one statement.
        var latest = new LinkedHashMap<Long, OrderWrite>();
        // Both execution sides are emitted together by one committed command. The temporary
        // map is limited to this SQL batch; no order or execution history is cached in memory.
        Set<Long> userTradedOrders = new HashSet<>();
        Map<String, RealtimeFrame> executions = new HashMap<>();
        for (var frame : orders) {
            if (frame.productLine() != product)
                throw new IllegalArgumentException("cross-product projection frame");
            if (frame.kind() == RealtimeFrame.Kind.EXECUTION) {
                if (frame.payloadLength() != 26 || frame.userId() <= 0)
                    throw new IllegalArgumentException("invalid execution identity");
                var other = executions.remove(frame.entityId());
                if (other == null) executions.put(frame.entityId(), frame);
                else {
                    byte[] left = other.payload(), right = frame.payload();
                    if (!other.instrumentId().equals(frame.instrumentId()) || left[25] == right[25]
                            || !Arrays.equals(left, 8, 24, right, 8, 24))
                        throw new IllegalArgumentException("execution sides disagree");
                    if (!marketMakerAccounts.contains(frame.userId())
                            || !marketMakerAccounts.contains(other.userId())) {
                        userTradedOrders.add(ByteBuffer.wrap(left).order(ByteOrder.LITTLE_ENDIAN).getLong());
                        userTradedOrders.add(ByteBuffer.wrap(right).order(ByteOrder.LITTLE_ENDIAN).getLong());
                    }
                }
                continue;
            }
            if (frame.productLine() != product || frame.kind() != RealtimeFrame.Kind.ORDER)
                throw new IllegalArgumentException("cross-product or non-order projection frame");
            byte[] raw = frame.payload();
            var order = CoreStateQueryCodec.decodeOrderState(raw);
            if (order.productLine() != product || order.userId() != frame.userId()
                    || !order.instrumentId().equals(frame.instrumentId()))
                throw new IllegalArgumentException("order projection identity mismatch");
            var prior = latest.get(order.orderId());
            if (prior == null || prior.order().revision() < order.revision())
                latest.put(order.orderId(), new OrderWrite(order, raw));
        }
        if (!executions.isEmpty()) throw new IllegalArgumentException("incomplete execution pair");
        transaction.executeWithoutResult(status -> {
            // A stored maker order is the durable proof of a previous ordinary-user fill.
            // Reuse it across batches/restarts instead of checkpointing a second history index.
            var candidates = latest.values().stream().map(OrderWrite::order)
                    .filter(order -> marketMakerAccounts.contains(order.userId())
                            && order.executedQuantitySteps() > 0
                            && !userTradedOrders.contains(order.orderId()))
                    .map(CoreOrderStateView::orderId).toList();
            if (!candidates.isEmpty()) {
                var arguments = new ArrayList<Object>();
                arguments.add(product.name());
                arguments.addAll(candidates);
                userTradedOrders.addAll(jdbc.queryForList(
                        "SELECT order_id FROM core_order_projection WHERE product_line=? AND order_id IN ("
                                + String.join(",", Collections.nCopies(candidates.size(), "?")) + ")",
                        Long.class, arguments.toArray()));
            }
            latest.values().removeIf(write -> marketMakerAccounts.contains(write.order().userId())
                    && !userTradedOrders.contains(write.order().orderId()));
            jdbc.batchUpdate(UPSERT, latest.values(), 512, (statement, write) -> {
                var order = write.order();
                statement.setString(1, product.name());
                statement.setLong(2, order.orderId());
                statement.setLong(3, order.userId());
                statement.setString(4, order.clientOrderId().isEmpty() ? null : order.clientOrderId());
                statement.setString(5, order.instrumentId());
                statement.setString(6, order.status());
                statement.setLong(7, order.createdAtEpochMillis());
                statement.setLong(8, order.updatedAtEpochMillis());
                statement.setLong(9, order.clusterPosition());
                statement.setLong(10, order.revision());
                statement.setLong(11, exportSequence);
                statement.setBytes(12, write.raw());
            });
            jdbc.update("""
                    INSERT INTO core_projection_watermark(product_line,last_export_sequence)
                    VALUES (?,?) ON CONFLICT (product_line) DO UPDATE SET
                      last_export_sequence=EXCLUDED.last_export_sequence,updated_at=now()
                    WHERE core_projection_watermark.last_export_sequence < EXCLUDED.last_export_sequence
                    """, product.name(), exportSequence);
        });
    }
    private record OrderWrite(CoreOrderStateView order, byte[] raw) {}
}
