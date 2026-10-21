package com.surprising.aeron.service.state.settlement;

import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.admission.PlaceAdmissionEvent;
import com.surprising.aeron.service.state.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.ledger.RuntimeFundsAccumulator;
import com.surprising.aeron.service.state.ledger.RuntimeTreasuryDelta;
import com.surprising.aeron.service.state.order.LaneOrderResultTarget;
import com.surprising.aeron.service.state.order.OrderRuntime;
import java.util.List;

import static com.surprising.aeron.service.state.TradingRuntimeState.*;

/**
 * 下单步骤 4.1、4.3 与 4.6：准备结算事件、投递账户事实，并衔接批量项的完成收集。
 *
 * <p>阅读顺序：Owner 调用 prepareDirect 系列方法借用事件并填写命令输入；Matcher 在
 * MatcherSettlementEvent 中整理事实后，调用 publishMatcherOwned 直接投递所属账户。
 * 账户及财务回执到齐后，Owner 收集变化并通过 releaseMatcherSettlement 归还事件。
 * 产品资金计算在各结算处理器中，本类不重复结算。</p>
 *
 * <p>事件池只由 Owner 操作；publishMatcherOwned 是 Matcher 发布边界，不能因为本类持有
 * 运行时引用，就把所有方法都当成 Owner 线程方法。编号对应 service README，不构成新执行阶段。</p>
 */
public final class MatcherSettlementDispatcher {
    /** 所属运行时；准备与回收遵守 Owner 线程约束，事实投递遵守单 Matcher 发布约束。 */
    final TradingRuntimeState owner;

    public MatcherSettlementDispatcher(TradingRuntimeState owner) { this.owner = owner; }

    public MatcherSettlementEvent prepareDirect(long coreSequence, long commitSequence, long laneMask,
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

    public MatcherSettlementEvent prepareDirectAdmission(long sequence, long laneMask,
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

    public MatcherSettlementEvent prepareDirectReplacement(long sequence, long commitSequence, long laneMask,
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

    public MatcherSettlementEvent prepareDirectCancellation(long sequence, long userId, long orderId,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, LaneOrderResultTarget resultTarget) {
        owner.assertOwner();
        if (userId <= 0 || orderId <= 0) throw new IllegalArgumentException("cancel order is missing");
        long laneMask = owner.topology().accountLaneMask(userId);
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

    public MatcherSettlementEvent prepareDirectCancelBatch(long sequence, boolean finalChunk, long userId, long[] orders, int count,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities, long timestamp, long position,
            LaneOrderResultTarget resultTarget) {
        owner.assertOwner();
        if (count <= 0 || count > orders.length) throw new IllegalArgumentException("invalid cancel chunk");
        long mask = owner.topology().accountLaneMask(userId);
        owner.ensureMatcherSettlementDispatchCapacity(mask);
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        System.arraycopy(orders, 0, event.batchStorage(count).cancellationOrderIds, 0, count);
        event.prepareDirect(sequence, finalChunk ? sequence : 0, mask, timestamp, position, commandId, shard,
                owner, identities, count, List.of(), resultTarget);
        event.cancellation(userId);
        return event;
    }

    /**
     * 下单步骤 4.3：由单个 Matcher 发布方把同一事实送入实际路由 Lane 的分片 SPSC 队列。
     * FUSED 不创建账户工作线程，直接在当前执行线程应用各 Lane；入队成功不表示结算完成。
     * @param event 已准备结果且仍由发布租约保护的本代结算事件
     */
    void publishMatcherOwned(MatcherSettlementEvent event) {
        if (event == null || event.runtime() != owner || !event.direct()
                || !event.dispatched() || !event.resultPrepared())
            throw new IllegalStateException("invalid Matcher-owned settlement publication");
        if (event.dispatched() && event.queuedLaneMask() != 0)
            throw new IllegalStateException("Matcher settlement was already dispatched");
        long lanes = event.routedLaneMask();
        if (lanes == 0) throw new IllegalStateException("Matcher settlement has no routed Lane");
        if (!owner.accountLanesStarted()) {
            if (owner.isFusedExecution()) {
                while (lanes != 0) {
                    int laneId = Long.numberOfTrailingZeros(lanes);
                    lanes &= lanes - 1;
                    event.markLaneQueued(laneId);
                    event.execute(owner.accountLane(laneId));
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

    public void releaseMatcherSettlement(MatcherSettlementEvent event) {
        owner.assertOwner();
        if (event == null) return;
        event.clear();
        matcherSettlementEventPool.addFirst(event);
    }

    /** Retire a direct event whose preceding Lane admission rejected the order. */
    public void discardMatcherSettlement(MatcherSettlementEvent event) {
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
        if (!owner.orderBatchMutationScope() || !event.complete())
            throw new IllegalStateException("batch item settlement is not ready to collect");
        owner.assertAccountLanesHealthy();
        // Sequential items share a funds boundary: merge endpoints before collecting the item.
        long lanes = event.requiredLaneMask();
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            owner.accountRollback().balancePatch(laneId).mergeSequential(event.changes().balancePatches[laneId]);
        }
        return owner.collectMatcherSettlement(event, event.hasLedgerSettlement() ? funds : null, terminalOrderSink);
    }

}
