package com.surprising.aeron.service.state;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import java.util.List;

import static com.surprising.aeron.service.state.TradingRuntimeState.*;

/** 撮合后结算派发与完成收集；沿用现货和衍生品结算处理器，保持 Lane 序号。 */
final class MatcherSettlementDispatcher {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingRuntimeState owner;

    MatcherSettlementDispatcher(TradingRuntimeState owner) { this.owner = owner; }

    /** 当前执行范围复用的 aggregateTreasuryDelta 临时缓冲，不保存第二份业务状态。 */
    final RuntimeTreasuryDelta aggregateTreasuryDeltaScratch =
            new RuntimeTreasuryDelta(RuntimeTreasuryDelta.ORDER_BATCH_CAPACITY);

    /** 可复用的 matcherSettlementEvent 对象池；仅在消费者完成后回收。 */
    final java.util.ArrayDeque<MatcherSettlementEvent> matcherSettlementEventPool =
            new java.util.ArrayDeque<>();

    /** 当前执行范围复用的 matcherBatchValidation 临时缓冲，不保存第二份业务状态。 */
    final MatcherSettlementPlan.BatchValidationScratch matcherBatchValidationScratch =
            new MatcherSettlementPlan.BatchValidationScratch();

    public MatcherSettlementEvent dispatchMatcherSettlement(
            long coreSequence, long expectedLaneMask, long commitSequence,
            long commitTimestamp, long commitClusterPosition,
            MatcherSettlementPlan plan, CoreMatchingResult matchingResult,
            RuntimeIdentityRegistry identities) {
        return dispatchMatcherSettlement(coreSequence, expectedLaneMask, commitSequence,
                commitTimestamp, commitClusterPosition, plan, matchingResult, identities, false);
    }

    MatcherSettlementEvent dispatchMatcherSettlement(
            long coreSequence, long expectedLaneMask, long commitSequence,
            long commitTimestamp, long commitClusterPosition,
            MatcherSettlementPlan plan, CoreMatchingResult matchingResult,
            RuntimeIdentityRegistry identities, boolean captureIsolatedChanges) {
        owner.assertOwner();
        long validMask = owner.accountLanes.length == Long.SIZE ? -1L : (1L << owner.accountLanes.length) - 1L;
        if (coreSequence <= 0 || commitSequence < 0 || expectedLaneMask == 0
                || (expectedLaneMask & ~validMask) != 0
                || matchingResult == null || matchingResult.nativeCommand().coreSequence() != coreSequence
                || plan == null || plan.coreSequence() != coreSequence
                || plan.requiredLaneMask() != expectedLaneMask || identities == null) {
            throw new IllegalArgumentException("invalid matcher settlement lane command");
        }
        long takerOrderId = plan.takerOrderId();
        OrderRuntime taker = owner.order(takerOrderId);
        if (taker == null) throw new IllegalStateException("taker order is missing");
        CoreInstrumentState instrument = owner.instrument(identities.symbol(taker.symbolId()));
        if (instrument == null) throw new IllegalStateException("match instrument is missing");
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
        for (int laneId = 0; laneId < owner.accountLanes.length; laneId++) {
            if ((expectedLaneMask & 1L << laneId) == 0) continue;
            if (!owner.accountLanesStarted) {
                event.execute(owner.accountLanes[laneId]);
            } else {
                owner.accountLaneQueueHighWaterMarks[laneId] = Math.max(
                        owner.accountLaneQueueHighWaterMarks[laneId], owner.laneWorkers[laneId].depth() + 1);
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

    public RuntimeTreasuryDelta applyOrderBatchMatcherSettlement(
            long coreSequence, long expectedLaneMask, long takerOrderId,
            CoreMatchingResult matchingResult, RuntimeIdentityRegistry identities,
            TradingRuntimeState.TerminalOrderSink terminalOrderSink) {
        if (!owner.orderBatchMutationScope) {
            throw new IllegalStateException("blocking matcher settlement is restricted to one order batch");
        }
        OrderRuntime taker = owner.order(takerOrderId);
        if (taker == null) throw new IllegalStateException("taker order is missing");
        MatcherSettlementPlan plan = MatcherSettlementPlan.build(coreSequence, takerOrderId, taker.userId(),
                new long[]{takerOrderId}, matchingResult, owner, identities);
        if (plan.requiredLaneMask() != expectedLaneMask) {
            throw new IllegalStateException("matcher settlement lane mask mismatch");
        }
        MatcherSettlementEvent event = dispatchMatcherSettlement(coreSequence, expectedLaneMask,
                0, -1, -1, plan, matchingResult, identities, true);
        awaitOrderBatchMatcherSettlement(event, System.nanoTime() + TradingRuntimeState.ORDER_BATCH_SETTLEMENT_TIMEOUT_NANOS);
        // Sequential batch items share one funds boundary. Carry Lane-computed endpoints into
        // that boundary; appending an item delta separately would count the same mutation twice.
        for (int laneId = 0; laneId < owner.accountLanes.length; laneId++) {
            if ((expectedLaneMask & (1L << laneId)) != 0) {
                owner.patchBalancesBeforeByLane[laneId].mergeSequential(event.changes().balancePatches[laneId]);
            }
        }
        RuntimeTreasuryDelta result = owner.collectMatcherSettlement(event, null, terminalOrderSink);
        releaseMatcherSettlement(event);
        return result;
    }

    void awaitOrderBatchMatcherSettlement(MatcherSettlementEvent event, long deadlineNanos) {
        // This is still a completion fence: no collection/recycling or next batch item on failure.
        // Check operational failure periodically without a clock read on every spin.
        int spins = 0;
        while (!event.complete()) {
            if ((spins++ & 1_023) == 0) {
                owner.assertAccountLanesHealthy();
                if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadlineNanos >= 0) {
                    throw new IllegalStateException("order batch matcher settlement interrupted or timed out");
                }
            }
            Thread.onSpinWait();
        }
        owner.assertAccountLanesHealthy();
    }

    public MatcherSettlementEvent[] dispatchMatcherSettlementBatch(
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
        long validMask = owner.accountLanes.length == Long.SIZE ? -1L : (1L << owner.accountLanes.length) - 1L;
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        MatcherSettlementEvent.BatchStorage storage = event.batchStorage(takerOrderIds.length);
        MatcherSettlementPlan[] plans = storage.plans;
        CoreInstrumentState[] instruments = storage.instruments;
        int[] baseAssetIds = storage.baseAssetIds;
        int[] quoteAssetIds = storage.quoteAssetIds;
        int[] settleAssetIds = storage.settleAssetIds;
        boolean prepared = false;
        try {
            long batchLaneMask = 0;
            for (int index = 0; index < takerOrderIds.length; index++) {
                long takerOrderId = takerOrderIds[index];
                long expectedLaneMask = expectedLaneMasks[index];
                CoreMatchingResult matchingResult = matchingResults.get(index);
                if (takerOrderId <= 0 || expectedLaneMask == 0 || (expectedLaneMask & ~validMask) != 0
                        || matchingResult == null || matchingResult.nativeCommand().coreSequence() != coreSequence) {
                    throw new IllegalArgumentException("invalid matcher settlement item");
                }
                OrderRuntime taker = owner.order(takerOrderId);
                if (taker == null) throw new IllegalStateException("taker order is missing");
                MatcherSettlementPlan plan = MatcherSettlementPlan.build(coreSequence, takerOrderId, taker.userId(),
                        new long[]{takerOrderId}, matchingResult, owner, identities);
                if (plan.requiredLaneMask() != expectedLaneMask) {
                    throw new IllegalStateException("matcher settlement lane mask mismatch");
                }
                plans[index] = plan;
                CoreInstrumentState instrument = owner.instrument(identities.symbol(taker.symbolId()));
                if (instrument == null) throw new IllegalStateException("match instrument is missing");
                instruments[index] = instrument;
                baseAssetIds[index] = identities.assetId(instrument.baseAsset());
                quoteAssetIds[index] = identities.assetId(instrument.quoteAsset());
                settleAssetIds[index] = identities.assetId(instrument.settleAsset());
                batchLaneMask |= expectedLaneMask;
            }
            MatcherSettlementPlan.validateAndPrepareBatch(
                    takerOrderIds, matchingResults, owner, identities, matcherBatchValidationScratch);
            prepared = true; // Once preparation starts, failures are fatal; never recycle a partially prepared event.
            event.prepareBatch(coreSequence, batchLaneMask, commitTimestamp, commitClusterPosition,
                    plans, owner, identities, instruments,
                    baseAssetIds, quoteAssetIds, settleAssetIds, owner.accountLanes.length);
            for (int laneId = 0; laneId < owner.accountLanes.length; laneId++) {
                if ((batchLaneMask & 1L << laneId) == 0) continue;
                if (!owner.accountLanesStarted) event.execute(owner.accountLanes[laneId]);
                else {
                    owner.accountLaneQueueHighWaterMarks[laneId] = Math.max(
                            owner.accountLaneQueueHighWaterMarks[laneId], owner.laneWorkers[laneId].depth() + 1);
                    owner.laneWorkers[laneId].submit(event);
                }
            }
            return new MatcherSettlementEvent[]{event};
        } finally {
            if (!prepared) {
                event.discardBatchStorage();
                matcherSettlementEventPool.addFirst(event);
            }
        }
    }

    public MatcherSettlementEvent[] dispatchSpotMatcherSettlements(
            long coreSequence, List<Long> takerOrderIds, List<Long> expectedLaneMasks,
            List<CoreMatchingResult> matchingResults, RuntimeIdentityRegistry identities) {
        owner.assertOwner();
        if (owner.productLine.isDerivative() || coreSequence <= 0 || takerOrderIds == null
                || expectedLaneMasks == null || matchingResults == null || takerOrderIds.isEmpty()
                || takerOrderIds.size() != expectedLaneMasks.size()
                || takerOrderIds.size() != matchingResults.size() || identities == null) {
            throw new IllegalArgumentException("invalid spot matcher settlement batch");
        }
        long validMask = owner.accountLanes.length == Long.SIZE ? -1L : (1L << owner.accountLanes.length) - 1L;
        MatcherSettlementPlan[] plans = new MatcherSettlementPlan[takerOrderIds.size()];
        for (int index = 0; index < takerOrderIds.size(); index++) {
            long takerOrderId = takerOrderIds.get(index);
            long expectedLaneMask = expectedLaneMasks.get(index);
            CoreMatchingResult matchingResult = matchingResults.get(index);
            if (takerOrderId <= 0 || expectedLaneMask == 0 || (expectedLaneMask & ~validMask) != 0
                    || matchingResult == null || matchingResult.nativeCommand().coreSequence() != coreSequence) {
                throw new IllegalArgumentException("invalid spot matcher settlement item");
            }
            OrderRuntime taker = owner.order(takerOrderId);
            if (taker == null) throw new IllegalStateException("spot taker order is missing");
            RuntimeSpotMatchProcessor.validate(takerOrderId, matchingResult.matcherEvents(), owner);
            MatcherSettlementPlan plan = MatcherSettlementPlan.build(coreSequence, takerOrderId, taker.userId(),
                    new long[]{takerOrderId}, matchingResult, owner, identities);
            if (plan.requiredLaneMask() != expectedLaneMask) {
                throw new IllegalStateException("spot matcher settlement lane mask mismatch");
            }
            plans[index] = plan;
        }
        MatcherSettlementEvent[] events = new MatcherSettlementEvent[plans.length];
        for (int index = 0; index < plans.length; index++) {
            events[index] = dispatchMatcherSettlement(coreSequence, expectedLaneMasks.get(index), 0,
                    -1, -1, plans[index], matchingResults.get(index), identities);
        }
        return events;
    }

    public RuntimeTreasuryDelta collectMatcherSettlements(MatcherSettlementEvent[] events) {
        return collectMatcherSettlements(events, null, null);
    }

    public RuntimeTreasuryDelta collectMatcherSettlements(
            MatcherSettlementEvent[] events, RuntimeFundsAccumulator fundsAccumulator,
            TradingRuntimeState.TerminalOrderSink terminalOrderSink) {
        owner.assertOwner();
        if (events == null || events.length == 0) return null;
        aggregateTreasuryDeltaScratch.clear();
        for (MatcherSettlementEvent event : events) {
            if (event == null || !event.complete()) return null;
        }
        for (MatcherSettlementEvent event : events) {
            aggregateTreasuryDeltaScratch.merge(
                    owner.collectMatcherSettlement(event, fundsAccumulator, terminalOrderSink));
            releaseMatcherSettlement(event);
        }
        return aggregateTreasuryDeltaScratch;
    }

}
