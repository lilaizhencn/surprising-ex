package com.surprising.realtime.provider.export.service;

import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreTriggerOrderCodec;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.service.replay.CommittedTradeReplay;
import com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.provider.export.model.CommittedProjectionRows.DeliveryLedgerEntry;
import com.surprising.realtime.provider.export.model.CommittedProjectionRows.ExecutionWrite;
import com.surprising.realtime.provider.export.model.CommittedProjectionRows.OrderWrite;
import com.surprising.realtime.provider.export.model.CommittedProjectionRows.TriggerWrite;
import com.surprising.realtime.provider.export.repository.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import static com.surprising.realtime.provider.export.utils.CommittedCommandResults.requireSameResult;

/** Read model only: the committed Archive replay remains the authority for order state. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedOrderProjectionService {
    private final CommittedOrderRepository orderRows;
    private final CommittedExecutionRepository executionRows;
    private final CommittedTriggerRepository triggerRows;
    private final CommittedCommandResultRepository receiptRows;
    private final ProjectionWatermarkRepository watermarks;
    private final ExecutionInitializationRepository executionInitialization;
    private final TriggerInitializationRepository triggerInitialization;
    private final MarketMakerProjectionAccountsRepository makerAccounts;
    private final CommittedDeliveryLedgerRepository deliveryLedger;
    private final CommittedFundingPageRepository fundingPages;
    private final CommittedFundingPaymentRepository fundingPayments;
    private final CommittedFundingSettlementRepository fundingSettlements;
    private final TransactionTemplate transaction;
    private final CommittedTradingStatisticsService statistics;
    private final CommittedPortfolioHistoryService portfolio;
    private final CommittedAlgoProjectionService algos;

    public CommittedOrderProjectionService(
            @org.springframework.beans.factory.annotation.Qualifier("committedProjectionTransactions") TransactionTemplate transaction,
            CommittedOrderRepository orderRows,
            CommittedExecutionRepository executionRows,
            CommittedTriggerRepository triggerRows,
            CommittedCommandResultRepository receiptRows,
            ProjectionWatermarkRepository watermarks,
            ExecutionInitializationRepository executionInitialization,
            TriggerInitializationRepository triggerInitialization,
            MarketMakerProjectionAccountsRepository makerAccounts,
            CommittedDeliveryLedgerRepository deliveryLedger,
            CommittedFundingPageRepository fundingPages,
            CommittedFundingPaymentRepository fundingPayments,
            CommittedFundingSettlementRepository fundingSettlements,
            CommittedTradingStatisticsService statistics, CommittedPortfolioHistoryService portfolio, CommittedAlgoProjectionService algos) {
        this.transaction = transaction;
        this.statistics = statistics; this.portfolio = portfolio; this.algos = algos;
        this.orderRows = orderRows;
        this.executionRows = executionRows;
        this.triggerRows = triggerRows;
        this.receiptRows = receiptRows;
        this.watermarks = watermarks;
        this.executionInitialization = executionInitialization;
        this.triggerInitialization = triggerInitialization;
        this.makerAccounts = makerAccounts;
        this.deliveryLedger = deliveryLedger;
        this.fundingPages = fundingPages;
        this.fundingPayments = fundingPayments;
        this.fundingSettlements = fundingSettlements;
    }

    /** 一页交割现金变动原子入账；只写历史投影，不触发任何在线账户命令。 */
    public void persistDelivery(com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedDeliveryPage page) {
        transaction.executeWithoutResult(tx -> {
            String reference = page.product().name() + ':' + page.command().instrumentId() + ':' + page.command().settlementId();
            String account = page.product().accountTypeCode();
            String referenceType = page.product().isOptionProduct() ? "OPTION_EXERCISE" : "DELIVERY_SETTLEMENT";
            String reason = page.product().isOptionProduct() ? "期权行权" : "交割结算";
            var occurred = java.sql.Timestamp.from(java.time.Instant.ofEpochMilli(page.occurredAt()));
            for (var flow : page.cashFlows()) {
                deliveryLedger.insertOrVerify(new DeliveryLedgerEntry(flow.userId(), account, flow.asset(),
                        flow.amountUnits(), flow.balanceAfterUnits(), referenceType, reference,
                        page.command().instrumentId(), reason, occurred));
            }
        });
    }

    public CommittedAlgoProjectionService algos() { return algos; }

    /** Use a method so Spring proxies delegate to the initialized target instance. */
    public CommittedTradingStatisticsService statistics() { return statistics; }
    public CommittedPortfolioHistoryService portfolio() { return portfolio; }

    public boolean executionsInitialized(ProductLine product) { return executionInitialization.initialized(product); }
    public void initializeExecutions(ProductLine product, long position) { executionInitialization.initialize(product, position); }

    /** Finite history repair writes executions only, without the normal order watermark. */
    public void repairExecutions(ProductLine product, List<RealtimeFrame> frames) {
        var writes = committedExecutions(product, frames);
        transaction.executeWithoutResult(tx -> executionRows.persistExecutions(product, writes));
    }

    private List<ExecutionWrite> committedExecutions(ProductLine product, List<RealtimeFrame> frames) {
        if (frames.stream().noneMatch(f -> f.kind() == RealtimeFrame.Kind.TRADE)) return List.of();
        var writes = new ArrayList<ExecutionWrite>();
        // Metadata belongs to the command which produced this fill. A later amend must not
        // lend its fee rates or position side to an earlier execution in the same SQL batch.
        for (int start = 0; start < frames.size();) {
            long position = frames.get(start).sequence();
            int end = start + 1;
            while (end < frames.size() && frames.get(end).sequence() == position) end++;
            var command = frames.subList(start, end); start = end;
            if (command.stream().noneMatch(f -> f.kind() == RealtimeFrame.Kind.TRADE)) continue;
            var states = new HashMap<Long, CoreOrderStateView>();
            var legs = new HashMap<String, RealtimeFrame[]>();
            var trades = new ArrayList<RealtimeFrame>();
            for (var frame : command) {
                if (frame.productLine() != product) throw new IllegalArgumentException("cross-product execution frame");
                if (frame.kind() == RealtimeFrame.Kind.ORDER) {
                    var order = CoreStateQueryCodec.decodeOrderState(frame.payload());
                    if (order.productLine() != product || order.userId() != frame.userId()
                            || !order.instrumentId().equals(frame.instrumentId()) || !Long.toString(order.orderId()).equals(frame.entityId()))
                        throw new IllegalArgumentException("execution order identity mismatch");
                    var prior = states.get(order.orderId());
                    if (prior == null || prior.revision() < order.revision()) states.put(order.orderId(), order);
                } else if (frame.kind() == RealtimeFrame.Kind.EXECUTION) {
                    byte[] raw = frame.payload();
                    if (raw.length != 26 || raw[25] < 0 || raw[25] > 1) throw new IllegalArgumentException("invalid execution leg");
                    var pair = legs.computeIfAbsent(frame.entityId(), ignored -> new RealtimeFrame[2]);
                    if (pair[raw[25]] != null) throw new IllegalArgumentException("duplicate execution leg");
                    pair[raw[25]] = frame;
                } else if (frame.kind() == RealtimeFrame.Kind.TRADE) trades.add(frame);
            }
            int index = 0;
            for (var trade : trades) {
                var pair = legs.remove(trade.entityId());
                if (trade.payloadLength() != 25 || pair == null || pair[0] == null || pair[1] == null || position <= 0
                        || trade.timestamp() <= 0 || !trade.entityId().startsWith(product.name() + ":"))
                    throw new IllegalArgumentException("incomplete committed trade");
                var data = ByteBuffer.wrap(trade.payload()).order(ByteOrder.LITTLE_ENDIAN);
                long price = data.getLong(), quantity = data.getLong(); data.getLong(); int side = data.get();
                if (price <= 0 || quantity <= 0 || side < 0 || side > 1) throw new IllegalArgumentException("invalid committed trade value");
                var taker = executionOrder(pair[0], states, trade, price, quantity, side);
                var maker = executionOrder(pair[1], states, trade, price, quantity, 1 - side);
                writes.add(new ExecutionWrite(trade.entityId(), position, index++, taker.orderId(), maker.orderId(),
                        taker.userId(), maker.userId(), trade.instrumentId(), taker.side().name(), taker.positionSide().name(),
                        maker.positionSide().name(), taker.takerFeeRatePpm(), maker.makerFeeRatePpm(), price, quantity, trade.timestamp()));
            }
            if (!legs.isEmpty()) throw new IllegalArgumentException("execution without public trade");
        }
        return writes;
    }

    private CoreOrderStateView executionOrder(RealtimeFrame frame, Map<Long, CoreOrderStateView> states,
            RealtimeFrame trade, long price, long quantity, int side) {
        var data = ByteBuffer.wrap(frame.payload()).order(ByteOrder.LITTLE_ENDIAN);
        var order = states.get(data.getLong());
        if (order == null || order.userId() != frame.userId() || !order.instrumentId().equals(trade.instrumentId())
                || !frame.instrumentId().equals(trade.instrumentId()) || frame.timestamp() != trade.timestamp()
                || data.getLong() != price || data.getLong() != quantity || data.get() != side || order.side().ordinal() != side)
            throw new IllegalArgumentException("execution metadata mismatch");
        return order;
    }

    public void initializeTriggers(ProductLine product, CommittedTradeReplay replay, long checkpointPosition) {
        if (triggerInitialization.initialized(product)) return;
        long before = 0;
        while (true) {
            var page = replay.retainedTriggersBefore(before, 512);
            if (page.isEmpty()) break;
            var writes = page.stream().map(v -> new TriggerWrite(v, CoreTriggerOrderCodec.encodeList(List.of(v)))).toList();
            transaction.executeWithoutResult(tx -> triggerRows.persistTriggers(product, writes, replay.exportSequence()));
            long next = page.getLast().triggerOrderId();
            if (next <= 0 || before != 0 && next >= before)
                throw new IllegalStateException("nonadvancing retained trigger cursor");
            before = next;
        }
        // Page failures leave this marker absent. Replay/version checks make retries idempotent.
        triggerInitialization.initialize(product, checkpointPosition, replay.exportSequence());
    }

    /** One committed funding page and its nonzero ledger entries commit together. The
     * completed interval is published only after the full cursor chain is present. Funding
     * projection export_sequence uses the immutable cluster log position, including pages
     * which emit no order/trade frame; it is not the trade-export sequence namespace. */
    public void persistFunding(com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedFundingPage page) {
        transaction.executeWithoutResult(tx -> {
            var command = page.command();
            if (!fundingPages.insertOrVerify(page)) return;
            fundingPayments.persist(page);
            if (!page.progress().complete()) return;
            var cursors = fundingPages.cursors(page);
            long expectedCursor = 0;
            for (int index = 0; index < cursors.size(); index++) {
                var cursor = cursors.get(index);
                if (cursor.current() != expectedCursor || cursor.rate() != command.fundingRatePpm()
                        || cursor.complete() != (index == cursors.size() - 1))
                    throw new IllegalStateException("committed funding history has an incomplete cursor chain");
                expectedCursor = cursor.next();
            }
            fundingSettlements.persist(page, fundingPayments.totals(page));
        });
    }

    /** All orders and their visibility watermark commit together, before the export checkpoint. */
    public void persist(ProductLine product, List<RealtimeFrame> orders,
                 List<CommittedCommandResult> commandResults, long exportSequence) {
        persist(product, orders, commandResults, exportSequence, List.of());
    }

    public void persist(ProductLine product, List<RealtimeFrame> orders,
                 List<CommittedCommandResult> commandResults, long exportSequence,
                 List<com.surprising.aeron.protocol.CoreAlgoOrderView> algoChanges) {
        if (product == null || exportSequence < 0) throw new IllegalArgumentException("invalid order projection batch");
        if (orders.isEmpty() && commandResults.isEmpty() && algoChanges.isEmpty()) {
            // Commands without order changes must still advance the visibility watermark.
            transaction.executeWithoutResult(status -> watermarks.advanceWatermark(product, exportSequence));
            return;
        }
        // Read each committed batch from the admin catalog. Disabled strategies still own
        // their historical accounts; changing enablement must not reclassify old maker fills.
        Set<Long> marketMakerAccounts = makerAccounts.marketMakerAccounts(product);
        var receipts = retailCommandResults(product, commandResults, marketMakerAccounts);
        // This bounded, per-commit SQL batch owns only the latest supplied version of each order.
        // PostgreSQL multi-row ON CONFLICT cannot update the same key twice in one statement.
        var latest = new LinkedHashMap<Long, OrderWrite>();
        var triggers = new LinkedHashMap<Long, TriggerWrite>();
        // Both execution sides are emitted together by one committed command. The temporary
        // map is limited to this SQL batch; no order or execution history is cached in memory.
        Set<Long> userTradedOrders = new HashSet<>();
        Map<String, RealtimeFrame> executions = new HashMap<>();
        for (var frame : orders) {
            if (frame.productLine() != product)
                throw new IllegalArgumentException("cross-product projection frame");
            if (frame.kind() == RealtimeFrame.Kind.TRADE) continue;
            if (frame.kind() == RealtimeFrame.Kind.TRIGGER) {
                var decoded = CoreTriggerOrderCodec.decodeList(frame.payload());
                if (decoded.size() != 1) throw new IllegalArgumentException("trigger frame must contain one entity");
                var value = decoded.getFirst();
                if (value.productLine() != product || value.userId() != frame.userId()
                        || !value.instrumentId().equals(frame.instrumentId()) || !Long.toString(value.triggerOrderId()).equals(frame.entityId()))
                    throw new IllegalArgumentException("trigger projection identity mismatch");
                var prior = triggers.get(value.triggerOrderId());
                if (prior != null && (prior.state().userId() != value.userId()
                        || !prior.state().instrumentId().equals(value.instrumentId())
                        || !prior.state().clientTriggerOrderId().equals(value.clientTriggerOrderId())
                        || prior.state().createdAtEpochMillis() != value.createdAtEpochMillis()
                        || prior.state().revision() == value.revision() && !Arrays.equals(prior.raw(), frame.payload())))
                    throw new IllegalStateException("conflicting committed trigger state");
                if (prior == null || prior.state().revision() < value.revision())
                    triggers.put(value.triggerOrderId(), new TriggerWrite(value, frame.payload()));
                continue;
            }
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
        var fills = committedExecutions(product, orders);
        transaction.executeWithoutResult(status -> {
            statistics.persist(product,commandResults);
            algos.persist(product, algoChanges, exportSequence);
            receiptRows.persistCommandResults(product, receipts);
            executionRows.persistExecutions(product, fills);
            triggerRows.persistTriggers(product, triggers.values(), exportSequence);
            // A stored maker order is the durable proof of a previous ordinary-user fill.
            // Reuse it across batches/restarts instead of checkpointing a second history index.
            var candidates = latest.values().stream().map(OrderWrite::order)
                    .filter(order -> marketMakerAccounts.contains(order.userId())
                            && order.executedQuantitySteps() > 0
                            && !userTradedOrders.contains(order.orderId()))
                    .map(CoreOrderStateView::orderId).toList();
            if (!candidates.isEmpty()) {
                userTradedOrders.addAll(orderRows.existingOrders(product, candidates));
            }
            latest.values().removeIf(write -> marketMakerAccounts.contains(write.order().userId())
                    && !userTradedOrders.contains(write.order().orderId()));
            orderRows.persist(product, latest.values(), exportSequence);
            watermarks.advanceWatermark(product, exportSequence);
        });
    }

    /** Receipt backfill owns a separate checkpoint; it must not advance the order watermark. */
    public void repairCommandResults(ProductLine product, List<CommittedCommandResult> results) {
        if (product == null) throw new IllegalArgumentException("product is required");
        if (results.isEmpty()) return;
        var receipts = retailCommandResults(product, results, makerAccounts.marketMakerAccounts(product));
        transaction.executeWithoutResult(tx -> receiptRows.persistCommandResults(product, receipts));
    }

    private static Map<UUID, CommittedCommandResult> retailCommandResults(ProductLine product,
            List<CommittedCommandResult> results, Set<Long> marketMakerAccounts) {
        var receipts = new LinkedHashMap<UUID, CommittedCommandResult>();
        for (var receipt : results) {
            if (receipt.product() != product) throw new IllegalArgumentException("cross-product command result");
            if (receipt.code() == com.surprising.aeron.protocol.CoreResultCode.IDEMPOTENCY_CONFLICT) continue;
            // Do not retain every transient quote from accounts owned by a maker strategy.
            boolean protection = switch (receipt.commandType()) {
                case PLACE_TRIGGER_ORDER, PLACE_TRIGGER_OCO_PAIR, CANCEL_TRIGGER_ORDER -> true;
                default -> false;
            };
            if (marketMakerAccounts.contains(receipt.userId()) && !protection) continue;
            var prior = receipts.putIfAbsent(receipt.commandId(), receipt);
            if (prior != null) {
                requireSameResult(prior, receipt);
            }
        }
        return receipts;
    }

}
