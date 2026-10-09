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

    /** One committed funding page and its nonzero ledger entries commit together. The
     * completed interval is published only after the full cursor chain is present. Funding
     * projection export_sequence uses the immutable cluster log position, including pages
     * which emit no order/trade frame; it is not the trade-export sequence namespace. */
    void persistFunding(com.surprising.aeron.service.orchestration.CommittedFundingPage page) {
        transaction.executeWithoutResult(tx -> {
            var command = page.command();
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                    page.product().name() + ':' + command.instrumentId() + ':' + command.settlementId());
            int inserted = jdbc.update("""
                    INSERT INTO core_funding_page_projection(product_line,cluster_position,instrument_id,
                      settlement_id,cursor_user_id,next_cursor_user_id,funding_rate_ppm,complete,occurred_at_epoch_ms)
                    VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                    """, page.product().name(), page.clusterPosition(), command.instrumentId(), command.settlementId(),
                    command.cursorUserId(), page.progress().nextCursorUserId(), command.fundingRatePpm(),
                    page.progress().complete(), page.occurredAt());
            if (inserted == 0) {
                Integer matches = jdbc.queryForObject("""
                        SELECT count(*) FROM core_funding_page_projection WHERE product_line=? AND cluster_position=?
                          AND instrument_id=? AND settlement_id=? AND cursor_user_id=? AND next_cursor_user_id=?
                          AND funding_rate_ppm=? AND complete=? AND occurred_at_epoch_ms=?
                        """, Integer.class, page.product().name(), page.clusterPosition(), command.instrumentId(),
                        command.settlementId(), command.cursorUserId(), page.progress().nextCursorUserId(),
                        command.fundingRatePpm(), page.progress().complete(), page.occurredAt());
                if (matches == null || matches != 1)
                    throw new IllegalStateException("conflicting committed funding page identity");
                return;
            }
            if (!page.payments().isEmpty()) jdbc.batchUpdate("""
                    INSERT INTO core_funding_payment_projection(product_line,export_sequence,payment_index,
                      settlement_id,user_id,instrument_id,margin_mode,position_side,asset,signed_quantity_steps,
                      notional_units,funding_rate_ppm,amount_units,occurred_at_epoch_ms)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                @Override public int getBatchSize() { return page.payments().size(); }
                @Override public void setValues(java.sql.PreparedStatement s, int index) throws java.sql.SQLException {
                    var payment = page.payments().get(index);
                    s.setString(1, page.product().name()); s.setLong(2, page.clusterPosition()); s.setInt(3, index);
                    s.setLong(4, payment.settlementId()); s.setLong(5, payment.userId()); s.setString(6, payment.instrumentId());
                    s.setString(7, payment.marginMode().name()); s.setString(8, payment.positionSide().name());
                    s.setString(9, payment.asset()); s.setLong(10, payment.signedQuantitySteps());
                    s.setLong(11, payment.notionalUnits()); s.setLong(12, payment.fundingRatePpm());
                    s.setLong(13, payment.amountUnits()); s.setLong(14, page.occurredAt());
                }
            });
            if (!page.progress().complete()) return;
            var cursors = jdbc.query("""
                    SELECT cursor_user_id,next_cursor_user_id,funding_rate_ppm,complete
                      FROM core_funding_page_projection
                     WHERE product_line=? AND instrument_id=? AND settlement_id=? ORDER BY cluster_position
                    """, (rs, row) -> new FundingCursor(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getBoolean(4)),
                    page.product().name(), command.instrumentId(), command.settlementId());
            long expectedCursor = 0;
            for (int index = 0; index < cursors.size(); index++) {
                var cursor = cursors.get(index);
                if (cursor.current() != expectedCursor || cursor.rate() != command.fundingRatePpm()
                        || cursor.complete() != (index == cursors.size() - 1))
                    throw new IllegalStateException("committed funding history has an incomplete cursor chain");
                expectedCursor = cursor.next();
            }
            jdbc.update("""
                    INSERT INTO core_funding_settlement_projection(product_line,settlement_id,export_sequence,
                      instrument_id,funding_rate_ppm,command_status,result_code,total_long_payment_units,
                      total_short_payment_units,position_count,occurred_at_epoch_ms)
                    SELECT ?,?,?,?,?,'APPLIED',?,
                      COALESCE(sum(amount_units) FILTER (WHERE signed_quantity_steps>0),0),
                      COALESCE(sum(amount_units) FILTER (WHERE signed_quantity_steps<0),0),count(*)::integer,?
                    FROM core_funding_payment_projection WHERE product_line=? AND instrument_id=? AND settlement_id=?
                    ON CONFLICT (product_line,instrument_id,settlement_id) DO NOTHING
                    """, page.product().name(), command.settlementId(), page.clusterPosition(), command.instrumentId(),
                    command.fundingRatePpm(), page.resultCode(), page.occurredAt(),
                    page.product().name(), command.instrumentId(), command.settlementId());
        });
    }

    private record FundingCursor(long current, long next, long rate, boolean complete) {}

    /** All orders and their visibility watermark commit together, before the export checkpoint. */
    void persist(ProductLine product, List<RealtimeFrame> orders, long exportSequence) {
        if (product == null || exportSequence < 0) throw new IllegalArgumentException("invalid order projection batch");
        if (orders.isEmpty()) {
            // Commands without order changes must still advance the visibility watermark.
            transaction.executeWithoutResult(status -> advanceWatermark(product, exportSequence));
            return;
        }
        // Read each committed batch from the admin catalog. Disabled strategies still own
        // their historical accounts; changing enablement must not reclassify old maker fills.
        Set<Long> marketMakerAccounts = new HashSet<>(jdbc.queryForList(
                """
                SELECT unnest(account_ids) FROM market_maker_strategies WHERE product_line=?
                UNION SELECT value::bigint FROM market_maker_business_settings,
                    jsonb_array_elements_text(settings->'trade'->'accountIds')
                    WHERE product_line=?
                """, Long.class, product.name(), product.name()));
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
            advanceWatermark(product, exportSequence);
        });
    }
    private void advanceWatermark(ProductLine product, long exportSequence) {
        jdbc.update("""
                INSERT INTO core_projection_watermark(product_line,last_export_sequence)
                VALUES (?,?) ON CONFLICT (product_line) DO UPDATE SET
                  last_export_sequence=EXCLUDED.last_export_sequence,updated_at=now()
                WHERE core_projection_watermark.last_export_sequence < EXCLUDED.last_export_sequence
                """, product.name(), exportSequence);
    }

    private record OrderWrite(CoreOrderStateView order, byte[] raw) {}
}
