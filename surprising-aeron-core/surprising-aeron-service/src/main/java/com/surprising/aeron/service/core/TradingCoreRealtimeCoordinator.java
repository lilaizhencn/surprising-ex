package com.surprising.aeron.service.core;

import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.protocol.CoreOrderBookView;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderCommand;
import com.surprising.aeron.service.order.ResolvedPlaceOrder;
import com.surprising.aeron.service.realtime.TradingRealtimeBoundary;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.concurrent.CompletableFuture;

/** 实时快照、盘口与成交事件捕获协调器。 */
final class TradingCoreRealtimeCoordinator {
    private final TradingCore core;
    private CompletableFuture<TradingRealtimeBoundary.UserSnapshot> pendingRealtimeSnapshot;

    TradingCoreRealtimeCoordinator(TradingCore core) {
        this.core = core;
    }

    TradingRealtimeBoundary.StateCapture attachRealtime(RealtimeOutbox outbox) {
        core.state.realtimeCapture = new TradingRealtimeBoundary.StateCapture(
                outbox, core.productLine, core.identities);
        core.accountBook.realtimeCapture(core.state.realtimeCapture);
        return core.state.realtimeCapture;
    }

    boolean realtimeSnapshotPending() {
        return pendingRealtimeSnapshot != null;
    }

    int pollRealtimeSnapshot() {
        if (pendingRealtimeSnapshot == null || !pendingRealtimeSnapshot.isDone()) return 0;
        var future = pendingRealtimeSnapshot;
        pendingRealtimeSnapshot = null;
        try {
            var snapshot = future.join();
            core.state.realtimeCapture.begin(core.state.realtimeSnapshotPosition,
                    core.state.realtimeSnapshotTimestamp, core.state.realtimeSnapshotId);
            core.state.realtimeCapture.snapshot(core.state.realtimeSnapshotUser,
                    snapshot, core.state.realtimeSnapshotExportSequence);
            core.state.realtimeCapture.commit();
        } catch (RuntimeException failure) {
            core.state.realtimeCapture.failed();
            core.state.realtimeCapture.begin(core.state.realtimeSnapshotPosition,
                    core.state.realtimeSnapshotTimestamp, core.state.realtimeSnapshotId);
            core.state.realtimeCapture.emit(RealtimeFrame.Kind.SNAPSHOT_UNAVAILABLE,
                    core.state.realtimeSnapshotUser, "", "", new byte[0]);
            core.state.realtimeCapture.commit();
        }
        return 1;
    }

    int pollRealtimeBook() {
        if (core.state.realtimeBook == null || !core.state.realtimeBook.isDone()) return 0;
        var future = core.state.realtimeBook;
        core.state.realtimeBook = null;
        try {
            var levels = future.join();
            core.state.realtimeCapture.begin(core.state.realtimeBookPosition, core.state.realtimeBookTimestamp, 0);
            core.state.realtimeCapture.emit(RealtimeFrame.Kind.BOOK, 0,
                    core.state.realtimeBookSymbol, core.state.realtimeBookSymbol,
                    CoreStateQueryCodec.encodeOrderBookView(
                            new CoreOrderBookView(core.state.realtimeBookPosition, levels)));
            core.state.realtimeCapture.commit();
        } catch (RuntimeException failure) {
            core.state.realtimeCapture.failed();
        }
        return 1;
    }

    void captureRealtimeSnapshot(long userId, long snapshotId, long position, long timestamp) {
        if (core.state.realtimeCapture == null || pendingRealtimeSnapshot != null) return;
        core.assertClusterCallbackComplete();
        core.state.realtimeSnapshotExportSequence = core.realtimeExportSequence();
        core.state.realtimeSnapshotUser = userId;
        core.state.realtimeSnapshotId = snapshotId;
        core.state.realtimeSnapshotPosition = position;
        core.state.realtimeSnapshotTimestamp = timestamp;
        try {
            pendingRealtimeSnapshot = core.accountBook.realtimeSnapshot(userId);
        } catch (RuntimeException failure) {
            core.state.realtimeCapture.failed();
        }
    }

    void captureRealtimeBook(String instrumentId, long position, long timestamp) {
        if (core.state.realtimeCapture == null || core.state.realtimeBook != null) return;
        core.assertClusterCallbackComplete();
        core.state.realtimeBookSymbol = instrumentId;
        core.state.realtimeBookPosition = position;
        core.state.realtimeBookTimestamp = timestamp;
        try {
            core.state.realtimeBook = core.matcherPipeline().readAtSubmissionFence(
                    core.matchingAdapter.matcherShardId(instrumentId),
                    () -> core.matchingAdapter.orderBookLevels(instrumentId, 50));
        } catch (RuntimeException failure) {
            core.state.realtimeCapture.failed();
        }
    }

    void captureRealtimeTrades(OrderCommand pending) {
        var plan = pending.settlementPlan();
        Order taker = pending.realtimeTakerOrder();
        ResolvedPlaceOrder resolvedTaker = pending.realtimeResolvedTakerOrder();
        pending.realtimeTakerOrder(null);
        pending.realtimeResolvedTakerOrder(null);
        if (core.state.realtimeCapture == null || !core.state.realtimeCapture.active()) return;
        try {
            for (int index = 0; index < plan.matcherEventCount(); index++) {
                MatcherEvent match = plan.matcherEvent(index);
                if (match.eventType() != MatcherEventType.TRADE) continue;
                if (resolvedTaker != null) {
                    core.state.realtimeCapture.trade(pending.command().header().userId(), resolvedTaker.orderId(),
                            resolvedTaker.symbolId(), resolvedTaker.side(), plan.coreSequence(), index,
                            match.price(), match.size(), match.matchedOrderId(), match.matchedOrderUid());
                } else {
                    core.state.realtimeCapture.trade(taker, plan.coreSequence(), index, match.price(), match.size(),
                            match.matchedOrderId(), match.matchedOrderUid());
                }
            }
        } catch (RuntimeException failure) {
            core.state.realtimeCapture.failed();
        }
    }
}
