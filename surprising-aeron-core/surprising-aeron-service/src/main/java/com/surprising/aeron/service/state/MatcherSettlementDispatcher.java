package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

import com.surprising.aeron.service.matching.MatchingResult;
import java.util.List;

import static com.surprising.aeron.service.state.TradingRuntimeState.*;

/** 撮合后结算派发与完成收集；沿用现货和衍生品结算处理器，保持 Lane 序号。 */
final class MatcherSettlementDispatcher {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingRuntimeState owner;

    MatcherSettlementDispatcher(TradingRuntimeState owner) { this.owner = owner; }

    MatcherSettlementEvent prepareDirect(long coreSequence, long commitSequence, long laneMask,
            OrderRuntime single, OrderRuntime[] orders, int count, java.util.UUID commandId, int shard,
            RuntimeIdentityRegistry identities, long timestamp, long position,
            List<Long> cancellations, LaneOrderResultTarget target) {
        return prepareDirect(coreSequence, commitSequence, laneMask, single, orders, count, commandId, shard,
                identities, timestamp, position, cancellations, target, null);
    }

    private MatcherSettlementEvent prepareDirect(long coreSequence, long commitSequence, long laneMask,
            OrderRuntime single, OrderRuntime[] orders, int count, java.util.UUID commandId, int shard,
            RuntimeIdentityRegistry identities, long timestamp, long position,
            List<Long> cancellations, LaneOrderResultTarget target,
            com.surprising.aeron.service.command.order.ResolvedMatchingAdmission replacement) {
        owner.assertOwner();
        if (count <= 0 || replacement == null && single == null && (orders == null || count > orders.length))
            throw new IllegalArgumentException("direct settlement admission is missing");
        owner.ensureMatcherSettlementDispatchCapacity(laneMask);
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        MatcherSettlementEvent.BatchStorage storage = event.batchStorage(count);
        if (replacement != null) {
            if (count != 1) throw new IllegalStateException("replacement must have one admission slot");
            var resolved = replacement.resolved();
            single = storage.replacementOrder = OrderRuntime.prepareCommandOrder(storage.replacementOrder,
                    owner.productLine(), replacement.userId(), resolved, commandId, resolved.symbolId(), timestamp, position);
        }
        for (int index = 0; index < count; index++) {
            OrderRuntime order = single == null ? orders[index] : single;
            if (order == null) throw new IllegalStateException("direct settlement order was not admitted");
            storage.admittedOrders[index] = order;
            int prior = storage.metadataSlots.get(order.symbolId());
            if (prior < 0) {
                CoreInstrument instrument = owner.instrument(identities.instrumentId(order.symbolId()));
                if (instrument == null || instrument != order.instrument())
                    throw new IllegalStateException("direct settlement instrument changed");
                storage.instruments[index] = instrument;
                storage.baseAssetIds[index] = identities.assetId(instrument.baseAsset());
                storage.quoteAssetIds[index] = identities.assetId(instrument.quoteAsset());
                storage.settleAssetIds[index] = identities.assetId(instrument.settleAsset());
                storage.metadataSlots.put(order.symbolId(), index);
            } else {
                storage.instruments[index] = storage.instruments[prior];
                storage.baseAssetIds[index] = storage.baseAssetIds[prior];
                storage.quoteAssetIds[index] = storage.quoteAssetIds[prior];
                storage.settleAssetIds[index] = storage.settleAssetIds[prior];
            }
        }
        event.prepareDirect(coreSequence, commitSequence, laneMask, timestamp, position, commandId, shard,
                owner, identities, count, cancellations, target);
        return event;
    }

    MatcherSettlementEvent prepareDirectAdmission(long sequence, long laneMask,
            PlaceAdmissionEvent admission, java.util.UUID commandId, int shard,
            RuntimeIdentityRegistry identities, long timestamp, long position,
            List<Long> cancellations, LaneOrderResultTarget target) {
        owner.assertOwner();
        if (admission == null) throw new IllegalArgumentException("place admission is missing");
        ResolvedPlaceOrder resolved = admission.preparedOrder();
        owner.ensureMatcherSettlementDispatchCapacity(laneMask);
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        MatcherSettlementEvent.BatchStorage storage = event.batchStorage(1);
        CoreInstrument instrument = owner.instrument(identities.instrumentId(resolved.symbolId()));
        if (instrument == null || instrument != resolved.instrument()) {
            throw new IllegalStateException("direct settlement instrument changed");
        }
        storage.instruments[0] = instrument;
        storage.baseAssetIds[0] = identities.assetId(instrument.baseAsset());
        storage.quoteAssetIds[0] = identities.assetId(instrument.quoteAsset());
        storage.settleAssetIds[0] = identities.assetId(instrument.settleAsset());
        storage.metadataSlots.put(resolved.symbolId(), 0);
        event.prepareDirect(sequence, laneMask, timestamp, position, commandId, shard,
                owner, identities, 1, cancellations, target);
        event.admissionRoute(admission.laneId());
        event.admissionOrder(resolved, admission.userId());
        return event;
    }

    MatcherSettlementEvent prepareDirectReplacement(long sequence, long commitSequence, long laneMask,
            com.surprising.aeron.service.command.order.ResolvedMatchingAdmission admission,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, List<Long> cancellations, LaneOrderResultTarget resultTarget) {
        owner.assertOwner();
        var resolved = admission.resolved();
        MatcherSettlementEvent event = prepareDirect(sequence, commitSequence, laneMask, null, null, 1,
                commandId, shard, identities, timestamp, position, cancellations, resultTarget, admission);
        event.replacement(admission, identities.assetId(resolved.reservationAsset()));
        return event;
    }

    MatcherSettlementEvent prepareDirectCancellation(long sequence, long userId, long orderId,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, LaneOrderResultTarget resultTarget) {
        owner.assertOwner();
        if (userId <= 0 || orderId <= 0) throw new IllegalArgumentException("cancel order is missing");
        long laneMask = owner.topology.accountLaneMask(userId);
        owner.ensureMatcherSettlementDispatchCapacity(laneMask);
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        event.batchStorage(1).cancellationOrderIds[0] = orderId;
        // 撤单不依赖当前合约配置，也不需要为成交解析资产或构建持仓身份。
        event.prepareDirect(sequence, laneMask, timestamp, position, commandId, shard,
                owner, identities, 1, List.of(), resultTarget);
        event.cancellation(userId);
        return event;
    }

    MatcherSettlementEvent prepareDirectCancelBatch(long sequence, boolean finalChunk, long userId, long[] orders, int count,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities, long timestamp, long position,
            LaneOrderResultTarget resultTarget) {
        owner.assertOwner();
        if (count <= 0 || count > orders.length) throw new IllegalArgumentException("invalid cancel chunk");
        long mask = owner.topology.accountLaneMask(userId);
        owner.ensureMatcherSettlementDispatchCapacity(mask);
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        System.arraycopy(orders, 0, event.batchStorage(count).cancellationOrderIds, 0, count);
        event.prepareDirect(sequence, finalChunk ? sequence : 0, mask, timestamp, position, commandId, shard,
                owner, identities, count, List.of(), resultTarget);
        event.cancellation(userId);
        return event;
    }

    /** Publish a completed Matcher fact directly into each routed Lane's shard SPSC mailbox. */
    void publishMatcherOwned(MatcherSettlementEvent event) {
        if (event == null || event.runtime() != owner || !event.direct()
                || !event.dispatched() || !event.resultPrepared())
            throw new IllegalStateException("invalid Matcher-owned settlement publication");
        if (event.dispatched() && event.queuedLaneMask() != 0)
            throw new IllegalStateException("Matcher settlement was already dispatched");
        long lanes = event.routedLaneMask();
        if (lanes == 0) throw new IllegalStateException("Matcher settlement has no routed Lane");
        if (!owner.accountLanesStarted) {
            if (owner.isFusedExecution()) {
                while (lanes != 0) {
                    int laneId = Long.numberOfTrailingZeros(lanes);
                    lanes &= lanes - 1;
                    event.markLaneQueued(laneId);
                    event.execute(owner.accountLanes[laneId]);
                }
            }
            return;
        }
        int shard = event.directShard();
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            event.markLaneQueued(laneId);
            owner.laneWorkers[laneId].publishMatcherSettlement(shard, event);
        }
    }

    /** 可复用的 matcherSettlementEvent 对象池；仅在消费者完成后回收。 */
    final java.util.ArrayDeque<MatcherSettlementEvent> matcherSettlementEventPool =
            new java.util.ArrayDeque<>();

    public MatcherSettlementEvent dispatchMatcherSettlement(
            long coreSequence, long expectedLaneMask, long commitSequence,
            long commitTimestamp, long commitClusterPosition,
            MatcherSettlementPlan plan, MatchingResult matchingResult,
            RuntimeIdentityRegistry identities) {
        return dispatchMatcherSettlement(coreSequence, expectedLaneMask, commitSequence,
                commitTimestamp, commitClusterPosition, plan, matchingResult, identities, false);
    }

    MatcherSettlementEvent dispatchMatcherSettlement(
            long coreSequence, long expectedLaneMask, long commitSequence,
            long commitTimestamp, long commitClusterPosition,
            MatcherSettlementPlan plan, MatchingResult matchingResult,
            RuntimeIdentityRegistry identities, boolean captureIsolatedChanges) {
        owner.assertOwner();
        long validMask = owner.accountLanes.length == Long.SIZE ? -1L : (1L << owner.accountLanes.length) - 1L;
        if (coreSequence <= 0 || commitSequence < 0 || expectedLaneMask == 0
                || (expectedLaneMask & ~validMask) != 0
                || matchingResult == null || matchingResult.nativeCoreSequence() != coreSequence
                || plan == null || plan.coreSequence() != coreSequence
                || plan.requiredLaneMask() != expectedLaneMask || identities == null) {
            throw new IllegalArgumentException("invalid matcher settlement lane command");
        }
        long takerOrderId = plan.takerOrderId();
        OrderRuntime taker = plan.admittedTaker();
        if (taker == null) taker = owner.order(takerOrderId);
        ResolvedPlaceOrder resolved = plan.resolvedTaker();
        if (taker == null && resolved == null) throw new IllegalStateException("taker order is missing");
        CoreInstrument instrument = taker == null ? resolved.instrument() : taker.instrument();
        if (instrument == null) throw new IllegalStateException("match instrument is missing");
        owner.ensureMatcherSettlementDispatchCapacity(expectedLaneMask);
        int baseAssetId = identities.assetId(instrument.baseAsset());
        int quoteAssetId = identities.assetId(instrument.quoteAsset());
        int settleAssetId = identities.assetId(instrument.settleAsset());
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        event.prepare(
                commitSequence, expectedLaneMask, commitTimestamp, commitClusterPosition,
                plan, owner, identities, instrument,
                baseAssetId, quoteAssetId, settleAssetId, owner.accountLanes.length,
                captureIsolatedChanges);
        long lanes = expectedLaneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if (!owner.accountLanesStarted) {
                event.execute(owner.accountLanes[laneId]);
            } else {
                owner.releaseOwnerLaneAccess();
                int highWater = owner.accountLanes[laneId].updateQueueHighWaterMark(owner.laneWorkers[laneId].depth() + 1);
                owner.accountLaneQueueHighWaterMarks[laneId] = highWater;
                owner.laneWorkers[laneId].submit(event);
            }
        }
        return event;
    }

    public void releaseMatcherSettlement(MatcherSettlementEvent event) {
        owner.assertOwner();
        if (event == null) return;
        event.clear();
        matcherSettlementEventPool.addFirst(event);
    }

    /** Retire a direct event whose preceding Lane admission rejected the order. */
    void discardMatcherSettlement(MatcherSettlementEvent event) {
        owner.assertOwner();
        if (event == null) return;
        if (!event.complete()) throw new IllegalStateException("rejected direct settlement is incomplete");
        if (event.hasChanges()) {
            owner.releaseMatcherSettlementChanges(event.takeChanges());
        }
        event.clear();
        matcherSettlementEventPool.addFirst(event);
    }

    public RuntimeTreasuryDelta collectOrderBatchMatcherSettlement(
            MatcherSettlementEvent event, RuntimeFundsAccumulator funds,
            TradingRuntimeState.TerminalOrderSink terminalOrderSink) {
        owner.assertOwner();
        if (!owner.orderBatchMutationScope || !event.complete())
            throw new IllegalStateException("batch item settlement is not ready to collect");
        owner.assertAccountLanesHealthy();
        // Sequential items share a funds boundary: merge endpoints before collecting the item.
        long lanes = event.requiredLaneMask();
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            owner.accountRollback.patchBalancesBeforeByLane[laneId].mergeSequential(event.changes().balancePatches[laneId]);
        }
        return owner.collectMatcherSettlement(event, event.hasLedgerSettlement() ? funds : null, terminalOrderSink);
    }

}
