package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreTradeFeeObservation;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.service.realtime.TradingRealtimeBoundary.StateCapture.LiquidationChange;
import com.surprising.aeron.service.replay.CommittedTradeReplay;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.provider.export.CommittedTradeExporter.ExportMode;
import com.surprising.realtime.provider.export.service.CommittedOrderProjectionService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Export-worker-owned batch: bounded by commands/frames and a delivery deadline. */
final class CommittedTradeExportBatch {
    private static final long MAX_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    private static final long CATCH_UP_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private final ProductLine product;
    private final CommittedOrderProjectionService orders;
    private final ReliableTradeKafkaSink sink;
    private final ExportMode mode;
    private final ArrayList<RealtimeFrame> trades = new ArrayList<>();
    private final ArrayList<RealtimeFrame> orderChanges = new ArrayList<>();
    private final ArrayList<CommittedTradeReplay.CommittedCommandResult> commandResults = new ArrayList<>();
    private final ArrayList<CoreAlgoOrderView> algoChanges = new ArrayList<>();
    private final ArrayList<CommittedTradeReplay.CommittedBalanceFlow> balanceFlows = new ArrayList<>();
    private final ArrayList<LiquidationChange> liquidationChanges = new ArrayList<>();
    private final ArrayList<CoreTradeFeeObservation> feeObservations = new ArrayList<>();
    private int messages;
    private long startedAt, flushedSequence = -1;
    private long maxDelayNanos = MAX_DELAY_NANOS;

    CommittedTradeExportBatch(ProductLine product, CommittedOrderProjectionService orders, ReliableTradeKafkaSink sink) {
        this(product, orders, sink, ExportMode.LIVE);
    }

    CommittedTradeExportBatch(ProductLine product, CommittedOrderProjectionService orders, ReliableTradeKafkaSink sink,
                              ExportMode mode) {
        this.product = product;
        this.orders = orders;
        this.sink = sink;
        this.mode = mode;
    }

    void addFees(List<CoreTradeFeeObservation> observations) { feeObservations.addAll(observations); }

    void catchUp(boolean behind) {
        maxDelayNanos = behind ? CATCH_UP_DELAY_NANOS : MAX_DELAY_NANOS;
    }

    void add(List<RealtimeFrame> changes, CommittedTradeReplay.CommittedCommandResult commandResult,
             long sequence, long now) {
        add(changes, commandResult, sequence, now, List.of());
    }

    void add(List<RealtimeFrame> changes, CommittedTradeReplay.CommittedCommandResult commandResult,
             long sequence, long now, List<CoreAlgoOrderView> algos) {
        add(changes, commandResult, sequence, now, algos, null);
    }

    void add(List<RealtimeFrame> changes, CommittedTradeReplay.CommittedCommandResult commandResult,
             long sequence, long now, List<CoreAlgoOrderView> algos,
             CommittedTradeReplay.CommittedBalanceFlow balanceFlow) {
        add(changes, commandResult, sequence, now, algos, balanceFlow, List.of());
    }

    void add(List<RealtimeFrame> changes, CommittedTradeReplay.CommittedCommandResult commandResult,
             long sequence, long now, List<CoreAlgoOrderView> algos,
             CommittedTradeReplay.CommittedBalanceFlow balanceFlow, List<LiquidationChange> liquidations) {
        if (mode == ExportMode.FUNDING_REPAIR || mode == ExportMode.DELIVERY_REPAIR) return;
        if (mode == ExportMode.LIVE || mode == ExportMode.LIQUIDATION_REPAIR) liquidationChanges.addAll(liquidations);
        if (mode == ExportMode.LIVE) algoChanges.addAll(algos);
        if ((mode == ExportMode.LIVE || mode == ExportMode.PORTFOLIO_REPAIR) && balanceFlow != null) balanceFlows.add(balanceFlow);
        if (messages++ == 0) startedAt = now;
        if (commandResult != null && mode != ExportMode.EXECUTION_REPAIR && mode != ExportMode.LIQUIDATION_REPAIR) commandResults.add(commandResult);
        for (var change : mode == ExportMode.LIVE || mode == ExportMode.EXECUTION_REPAIR ? changes : List.<RealtimeFrame>of()) {
            if (change.kind() == RealtimeFrame.Kind.TRADE && mode == ExportMode.LIVE) trades.add(change);
            orderChanges.add(change);
        }
        if (messages >= 256 || trades.size() + orderChanges.size() + algoChanges.size() + liquidationChanges.size() >= 4096
                || now - startedAt >= maxDelayNanos) flush(sequence);
    }

    void flushIfDue(long sequence, long now) {
        if (messages > 0 && now - startedAt >= maxDelayNanos) flush(sequence);
    }

    long waitNanos(long now, long maxWaitNanos) {
        return messages == 0 ? maxWaitNanos
                : Math.max(1, Math.min(maxWaitNanos, maxDelayNanos - (now - startedAt)));
    }

    void flush(long sequence) {
        if (mode == ExportMode.FUNDING_REPAIR || mode == ExportMode.DELIVERY_REPAIR) return;
        if (sequence != flushedSequence || !orderChanges.isEmpty() || !trades.isEmpty() || !commandResults.isEmpty() || !algoChanges.isEmpty() || !balanceFlows.isEmpty() || !liquidationChanges.isEmpty()) {
            if (mode == ExportMode.COMMAND_RESULT_REPAIR) orders.repairCommandResults(product, commandResults);
            else if (mode == ExportMode.EXECUTION_REPAIR) orders.repairExecutions(product, orderChanges);
            else if (mode == ExportMode.LIQUIDATION_REPAIR) orders.repairLiquidations(product, liquidationChanges);
            else if (mode == ExportMode.STATISTICS_REPAIR) orders.statistics().repair(product, commandResults);
            else if (mode == ExportMode.PORTFOLIO_REPAIR) orders.portfolio().persist(balanceFlows);
            else {
                orders.persist(product, orderChanges, commandResults, sequence, algoChanges, liquidationChanges, balanceFlows, feeObservations);
                sink.publish(trades);
            }
            // Neither the checkpoint nor this marker advances when either sink fails.
            flushedSequence = sequence;
        }
        feeObservations.clear();
        algoChanges.clear();
        liquidationChanges.clear();
        balanceFlows.clear();
        orderChanges.clear();
        commandResults.clear();
        trades.clear();
        messages = 0;
    }
}
