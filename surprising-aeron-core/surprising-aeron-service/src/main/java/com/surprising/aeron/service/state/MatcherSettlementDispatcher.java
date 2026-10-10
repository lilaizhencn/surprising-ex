package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

import com.surprising.aeron.service.matching.CoreMatchingResult;
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
        owner.assertOwner();
        if (count <= 0 || single == null && (orders == null || count > orders.length))
            throw new IllegalArgumentException("direct settlement admission is missing");
        owner.ensureMatcherSettlementDispatchCapacity(laneMask);
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        MatcherSettlementEvent.BatchStorage storage = event.batchStorage(count);
        for (int index = 0; index < count; index++) {
            OrderRuntime order = single == null ? orders[index] : single;
            if (order == null) throw new IllegalStateException("direct settlement order was not admitted");
            storage.admittedOrders[index] = order;
            int prior = storage.metadataSlots.getIfAbsent(order.symbolId(), -1);
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
        OrderRuntime order = TradingRuntimeState.preparedOrder(owner.productLine(), admission.userId(),
                resolved, commandId, resolved.symbolId(), timestamp, position);
        MatcherSettlementEvent event = prepareDirect(sequence, commitSequence, laneMask, order, null, 1,
                commandId, shard, identities, timestamp, position, cancellations, resultTarget);
        event.replacement(admission, identities.assetId(resolved.reservationAsset()));
        return event;
    }

    MatcherSettlementEvent prepareDirectCancellation(long sequence, OrderRuntime order,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, LaneOrderResultTarget resultTarget) {
        owner.assertOwner();
        if (order == null) throw new IllegalArgumentException("cancel order is missing");
        long laneMask = owner.topology.accountLaneMask(order.userId());
        owner.ensureMatcherSettlementDispatchCapacity(laneMask);
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        event.batchStorage(1).admittedOrders[0] = order;
        // 撤单不依赖当前合约配置，也不需要为成交解析资产或构建持仓身份。
        event.prepareDirect(sequence, laneMask, timestamp, position, commandId, shard,
                owner, identities, 1, List.of(), resultTarget);
        event.cancellation(order.userId());
        return event;
    }

    MatcherSettlementEvent prepareDirectCancelBatch(long sequence, boolean finalChunk, long userId, OrderRuntime[] orders, int count,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities, long timestamp, long position,
            LaneOrderResultTarget resultTarget) {
        owner.assertOwner();
        if (count <= 0 || count > orders.length) throw new IllegalArgumentException("invalid cancel chunk");
        long mask = owner.topology.accountLaneMask(userId);
        owner.ensureMatcherSettlementDispatchCapacity(mask);
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        System.arraycopy(orders, 0, event.batchStorage(count).admittedOrders, 0, count);
        event.prepareDirect(sequence, finalChunk ? sequence : 0, mask, timestamp, position, commandId, shard,
                owner, identities, count, List.of(), resultTarget);
        event.cancellation(userId);
        return event;
    }

    /** Register an Owner-dispatched event; the ordered commit head applies it after readiness. */
    void dispatchOwnerControlled(MatcherSettlementEvent event) {
        owner.assertOwner();
        if (event == null || event.runtime() != owner || !event.direct() || event.dispatched())
            throw new IllegalStateException("invalid direct Lane ordering slot");
        long mask = event.routedLaneMask();
        owner.ensureLaneWorkerCapacity(mask);
        owner.releaseOwnerLaneAccess();
        event.markDispatched();
        // Predispatch only registers ownership. Even an already-ready result must not apply
        // here: an earlier command for this Lane can still be waiting for its Matcher result.
        // The ordered Owner commit head executes the registered event after readiness.
    }

    /** Prepare the Matcher fact's dispatch metadata before publishing its readiness flag. */
    void publishMatcherOwned(MatcherSettlementEvent event) {
        if (event == null || event.runtime() != owner || !event.direct() || !event.resultPrepared())
            throw new IllegalStateException("invalid Matcher-owned settlement publication");
        if (event.routedLaneMask() == 0)
            throw new IllegalStateException("Matcher settlement has no routed Lane");
        if (!event.dispatched()) event.markDispatched();
        // Account mutation belongs to the Owner after readiness, never to this producer.
    }

    /** 可复用的 matcherSettlementEvent 对象池；仅在消费者完成后回收。 */
    final java.util.ArrayDeque<MatcherSettlementEvent> matcherSettlementEventPool =
            new java.util.ArrayDeque<>();

    /** 当前执行范围复用的 matcherBatchValidation 临时缓冲，不保存第二份业务状态。 */
    final MatcherSettlementPlan.BatchValidationScratch matcherBatchValidationScratch =
            new MatcherSettlementPlan.BatchValidationScratch();

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
            event.execute(owner.accountLanes[laneId]);
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

    public MatcherSettlementEvent dispatchOrderBatchMatcherSettlement(
            long coreSequence, long expectedLaneMask, long takerOrderId,
            MatchingResult matchingResult, RuntimeIdentityRegistry identities) {
        owner.assertOwner();
        if (!owner.orderBatchMutationScope) {
            throw new IllegalStateException("item settlement requires an order batch");
        }
        OrderRuntime taker = owner.order(takerOrderId);
        if (taker == null) throw new IllegalStateException("taker order is missing");
        MatcherSettlementPlan plan = MatcherSettlementPlan.buildSingleInto(
                new MatcherSettlementPlan(), coreSequence, takerOrderId, taker.userId(),
                matchingResult, owner, identities).rejectTaker(!matchingResult.accepted());
        if (plan.requiredLaneMask() != expectedLaneMask) {
            throw new IllegalStateException("matcher settlement lane mask mismatch");
        }
        owner.releaseOwnerLaneAccess();
        return dispatchMatcherSettlement(coreSequence, expectedLaneMask,
                0, -1, -1, plan, matchingResult, identities, true);
    }

    public RuntimeTreasuryDelta collectOrderBatchMatcherSettlement(
            MatcherSettlementEvent event, TradingRuntimeState.TerminalOrderSink terminalOrderSink) {
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
        return owner.collectMatcherSettlement(event, null, terminalOrderSink);
    }

    public MatcherSettlementEvent dispatchMatcherSettlementBatch(
            long coreSequence, long[] takerOrderIds, long[] expectedLaneMasks,
            List<CoreMatchingResult> matchingResults, RuntimeIdentityRegistry identities,
            long commitTimestamp, long commitClusterPosition) {
        owner.assertOwner();
        if (coreSequence <= 0 || takerOrderIds == null
                || expectedLaneMasks == null || matchingResults == null || takerOrderIds.length == 0
                || takerOrderIds.length != expectedLaneMasks.length
                || takerOrderIds.length != matchingResults.size() || identities == null
                || commitTimestamp < 0 || commitClusterPosition < 0) {
            throw new IllegalArgumentException("invalid matcher settlement batch");
        }
        return dispatchMatcherSettlementBatch(coreSequence, new SettlementBatchInput() {
            public int settlementCount() { return takerOrderIds.length; }
            public long settlementOrderId(int index) { return takerOrderIds[index]; }
            public long settlementLaneMask(int index) { return expectedLaneMasks[index]; }
            public com.surprising.aeron.service.matching.MatchingResult settlementResult(int index) {
                return matchingResults.get(index);
            }
        }, identities, commitTimestamp, commitClusterPosition);
    }

    MatcherSettlementEvent dispatchMatcherSettlementBatch(long coreSequence, SettlementBatchInput batch,
            RuntimeIdentityRegistry identities, long commitTimestamp, long commitClusterPosition) {
        owner.assertOwner();
        if (coreSequence <= 0 || batch == null || batch.settlementCount() <= 0 || identities == null
                || commitTimestamp < 0 || commitClusterPosition < 0)
            throw new IllegalArgumentException("invalid matcher settlement batch");
        long validMask = owner.accountLanes.length == Long.SIZE ? -1L : (1L << owner.accountLanes.length) - 1L;
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        MatcherSettlementEvent.BatchStorage storage = event.batchStorage(batch.settlementCount());
        MatcherSettlementPlan[] plans = storage.plans;
        CoreInstrument[] instruments = storage.instruments;
        int[] baseAssetIds = storage.baseAssetIds;
        int[] quoteAssetIds = storage.quoteAssetIds;
        int[] settleAssetIds = storage.settleAssetIds;
        matcherBatchValidationScratch.clear();
        boolean prepared = false;
        try {
            long batchLaneMask = 0;
            for (int index = 0; index < batch.settlementCount(); index++) {
                long takerOrderId = batch.settlementOrderId(index);
                long expectedLaneMask = batch.settlementLaneMask(index);
                com.surprising.aeron.service.matching.MatchingResult matchingResult = batch.settlementResult(index);
                if (takerOrderId <= 0 || expectedLaneMask == 0 || (expectedLaneMask & ~validMask) != 0
                        || matchingResult == null || matchingResult.nativeCoreSequence() != coreSequence) {
                    throw new IllegalArgumentException("invalid matcher settlement item");
                }
                OrderRuntime taker = batch.settlementOrder(index);
                if (taker == null) taker = owner.order(takerOrderId);
                if (taker == null) throw new IllegalStateException("taker order is missing");
                int slot = storage.metadataSlots.getIfAbsent(taker.symbolId(), -1);
                if (slot < 0) {
                    CoreInstrument instrument = owner.instrument(identities.instrumentId(taker.symbolId()));
                    if (instrument == null) throw new IllegalStateException("match instrument is missing");
                    instruments[index] = instrument;
                    baseAssetIds[index] = identities.assetId(instrument.baseAsset());
                    quoteAssetIds[index] = identities.assetId(instrument.quoteAsset());
                    settleAssetIds[index] = identities.assetId(instrument.settleAsset());
                    storage.metadataSlots.put(taker.symbolId(), index);
                } else {
                    instruments[index] = instruments[slot];
                    baseAssetIds[index] = baseAssetIds[slot];
                    quoteAssetIds[index] = quoteAssetIds[slot];
                    settleAssetIds[index] = settleAssetIds[slot];
                }
                MatcherSettlementPlan plan = MatcherSettlementPlan.buildBatchItem(coreSequence, taker,
                        instruments[index], matchingResult, owner, identities, matcherBatchValidationScratch, plans[index])
                        .rejectTaker(!matchingResult.accepted());
                if (plan.requiredLaneMask() != expectedLaneMask)
                    throw new IllegalStateException("matcher settlement lane mask mismatch");
                plans[index] = plan;
                batchLaneMask |= expectedLaneMask;
            }
            owner.ensureMatcherSettlementDispatchCapacity(batchLaneMask);
            prepared = true; // Once preparation starts, failures are fatal; never recycle a partially prepared event.
            event.prepareBatch(coreSequence, batchLaneMask, commitTimestamp, commitClusterPosition,
                    plans, batch.settlementCount(), owner, identities, instruments,
                    baseAssetIds, quoteAssetIds, settleAssetIds, owner.accountLanes.length);
            event.resultTarget = batch instanceof LaneOrderResultTarget target ? target : null;
            long lanes = batchLaneMask;
            while (lanes != 0) {
                int laneId = Long.numberOfTrailingZeros(lanes);
                lanes &= lanes - 1;
                event.execute(owner.accountLanes[laneId]);
            }
            return event;
        } finally {
            matcherBatchValidationScratch.clear();
            if (!prepared) {
                event.discardBatchStorage();
                matcherSettlementEventPool.addFirst(event);
            }
        }
    }

}
