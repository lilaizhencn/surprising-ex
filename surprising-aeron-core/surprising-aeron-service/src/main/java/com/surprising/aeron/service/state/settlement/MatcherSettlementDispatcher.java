package com.surprising.aeron.service.state.settlement;

import lombok.RequiredArgsConstructor;
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
 * 下单步骤 4.1、4.3 与 4.6 的交接入口：Owner 准备和回收事件，单个 Matcher 发布方直接投递所属账户，顺序批量收集时接续余额边界。产品资金计算仍由结算处理器完成。事件池只有 Owner 访问，不能把 Matcher 投递方法误当成 Owner 方法。
 * 构造约定：保存所属运行时；事件池随本派发器长期保留，事件完成且交出变化后才能归还。
 * 参数 owner：本派发器服务的交易运行时
 */
@RequiredArgsConstructor
public final class MatcherSettlementDispatcher {
    /** 所属运行时；准备及回收在 Owner，publishMatcherOwned 在单 Matcher 发布方执行。 */
    final TradingRuntimeState owner;


    /**
     * 为单笔或批量已准入订单准备直达结算事件，委托内部入口处理元数据和复用存储。
     * @param coreSequence Matcher 证据与本代结算使用的 Core 序号
     * @param commitSequence 完成本段账户工作时推进的 Lane 提交序号，零表示暂不推进最终水位
     * @param laneMask 命令准备时已知的账户 Lane 位图，单 Lane 路由可随成交发现 maker 后扩展
     * @param single 单笔已准入订单；批量传空时从 orders 有效前缀读取
     * @param orders 本次已准入订单数组，只读取 count 个有效项
     * @param count 本次结算的有效订单项数
     * @param commandId 本代复制命令 UUID，用于校验 Matcher 证据归属
     * @param shard 本命令固定的 Matcher 分片编号
     * @param identities 当前运行时资产、币对与账户身份字典
     * @param timestamp 复制日志中的业务时间戳
     * @param position 复制日志位置，供账户事实和终态元数据使用
     * @param cancellations 本命令准入授权的前置撤单编号列表，实际成功前缀随后由 Matcher 记录
     * @param target 在事件租约内接收所属账户最终订单值的目标，可为空
     */
    public MatcherSettlementEvent prepareDirect(long coreSequence, long commitSequence, long laneMask,
            OrderRuntime single, OrderRuntime[] orders, int count, java.util.UUID commandId, int shard,
            RuntimeIdentityRegistry identities, long timestamp, long position,
            List<Long> cancellations, LaneOrderResultTarget target) {
        return prepareDirect(coreSequence, commitSequence, laneMask, single, orders, count, commandId, shard,
                identities, timestamp, position, cancellations, target, null);
    }

    /**
     * Owner 检查订单输入并借用事件，改单时准备复用命令订单槽；同一币对只解析一次规范对象与资产编号，随后保存本代命令及结算输入，不在这里撮合或写账户。
     * @param coreSequence Matcher 证据与本代结算使用的 Core 序号
     * @param commitSequence 完成本段账户工作时推进的 Lane 提交序号，零表示暂不推进最终水位
     * @param laneMask 命令准备时已知的账户 Lane 位图，单 Lane 路由可随成交发现 maker 后扩展
     * @param single 单笔已准入订单；批量传空时从 orders 有效前缀读取
     * @param orders 本次已准入订单数组，只读取 count 个有效项
     * @param count 本次结算的有效订单项数
     * @param commandId 本代复制命令 UUID，用于校验 Matcher 证据归属
     * @param shard 本命令固定的 Matcher 分片编号
     * @param identities 当前运行时资产、币对与账户身份字典
     * @param timestamp 复制日志中的业务时间戳
     * @param position 复制日志位置，供账户事实和终态元数据使用
     * @param cancellations 本命令准入授权的前置撤单编号列表，实际成功前缀随后由 Matcher 记录
     * @param target 在事件租约内接收所属账户最终订单值的目标，可为空
     * @param replacement 改单准入结果，非空时使用本事件的替换订单槽
     */
    private MatcherSettlementEvent prepareDirect(long coreSequence, long commitSequence, long laneMask,
            OrderRuntime single, OrderRuntime[] orders, int count, java.util.UUID commandId, int shard,
            RuntimeIdentityRegistry identities, long timestamp, long position,
            List<Long> cancellations, LaneOrderResultTarget target,
            com.surprising.aeron.service.command.order.ResolvedMatchingAdmission replacement) {
        owner.assertOwner();
        if (count <= 0 || replacement == null && single == null && (orders == null || count > orders.length))
            throw new IllegalArgumentException("direct settlement admission is missing");
        owner.ensureMatcherSettlementDispatchCapacity(laneMask);
        // 本次从对象池借用或新建的结算事件。
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        // 该事件独占的可复用批量槽和币对元数据列。
        MatcherSettlementEvent.BatchStorage storage = event.batchStorage(count);
        if (replacement != null) {
            if (count != 1) throw new IllegalStateException("replacement must have one admission slot");
            // 准入已解析的订单输入，用于填充替换命令槽或保存币对信息。
            var resolved = replacement.resolved();
            single = storage.replacementOrder = OrderRuntime.prepareCommandOrder(storage.replacementOrder,
                    owner.productLine(), replacement.userId(), resolved, commandId, resolved.symbolId(), timestamp, position);
        }
        for (/* 当前准备的有效订单项下标。 */ int index = 0; index < count; index++) {
            // 当前需要交给 Matcher 的已准入订单值。
            OrderRuntime order = single == null ? orders[index] : single;
            if (order == null) throw new IllegalStateException("direct settlement order was not admitted");
            storage.admittedOrders[index] = order;
            // 同币对此前已填好元数据的第一项下标，负数表示尚未缓存。
            int prior = storage.metadataSlots.get(order.symbolId());
            if (prior < 0) {
                // 运行时当前规范币对对象，必须与准入输入中的对象相同。
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

    /**
     * 为普通下单保存不可变准入输入、规范币对及资产编号，登记准入所属 Lane；后续账户准入回执仍由 Matcher 消费并复制进事件。
     * @param sequence 当前复制命令的 Core 序号
     * @param laneMask 命令准备时已知的账户 Lane 位图，单 Lane 路由可随成交发现 maker 后扩展
     * @param admission 普通下单准入事件，已保存待准入的不可变订单输入
     * @param commandId 本代复制命令 UUID，用于校验 Matcher 证据归属
     * @param shard 本命令固定的 Matcher 分片编号
     * @param identities 当前运行时资产、币对与账户身份字典
     * @param timestamp 复制日志中的业务时间戳
     * @param position 复制日志位置，供账户事实和终态元数据使用
     * @param cancellations 本命令准入授权的前置撤单编号列表，实际成功前缀随后由 Matcher 记录
     * @param target 在事件租约内接收所属账户最终订单值的目标，可为空
     */
    public MatcherSettlementEvent prepareDirectAdmission(long sequence, long laneMask,
            PlaceAdmissionEvent admission, java.util.UUID commandId, int shard,
            RuntimeIdentityRegistry identities, long timestamp, long position,
            List<Long> cancellations, LaneOrderResultTarget target) {
        owner.assertOwner();
        if (admission == null) throw new IllegalArgumentException("place admission is missing");
        // 准入已解析的订单输入，用于填充替换命令槽或保存币对信息。
        ResolvedPlaceOrder resolved = admission.preparedOrder();
        owner.ensureMatcherSettlementDispatchCapacity(laneMask);
        // 本次从对象池借用或新建的结算事件。
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        // 该事件独占的可复用批量槽和币对元数据列。
        MatcherSettlementEvent.BatchStorage storage = event.batchStorage(1);
        // 运行时当前规范币对对象，必须与准入输入中的对象相同。
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

    /**
     * 复用单笔准备入口保存改单输入，并登记新预留资产编号供所属 Lane 应用替换。
     * @param sequence 当前复制命令的 Core 序号
     * @param commitSequence 完成本段账户工作时推进的 Lane 提交序号，零表示暂不推进最终水位
     * @param laneMask 命令准备时已知的账户 Lane 位图，单 Lane 路由可随成交发现 maker 后扩展
     * @param admission 已经完成校验的改单准入输入及原单身份
     * @param commandId 本代复制命令 UUID，用于校验 Matcher 证据归属
     * @param shard 本命令固定的 Matcher 分片编号
     * @param identities 当前运行时资产、币对与账户身份字典
     * @param timestamp 复制日志中的业务时间戳
     * @param position 复制日志位置，供账户事实和终态元数据使用
     * @param cancellations 本命令准入授权的前置撤单编号列表，实际成功前缀随后由 Matcher 记录
     * @param resultTarget 在事件租约内接收订单最终值的目标，可为空
     */
    public MatcherSettlementEvent prepareDirectReplacement(long sequence, long commitSequence, long laneMask,
            com.surprising.aeron.service.command.order.ResolvedMatchingAdmission admission,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, List<Long> cancellations, LaneOrderResultTarget resultTarget) {
        owner.assertOwner();
        // 准入已解析的订单输入，用于填充替换命令槽或保存币对信息。
        var resolved = admission.resolved();
        // 本次从对象池借用或新建的结算事件。
        MatcherSettlementEvent event = prepareDirect(sequence, commitSequence, laneMask, null, null, 1,
                commandId, shard, identities, timestamp, position, cancellations, resultTarget, admission);
        event.replacement(admission, identities.assetId(resolved.reservationAsset()));
        return event;
    }

    /**
     * 校验用户与订单编号，为用户所属 Lane 准备单笔撤单事件；撤单不解析当前币对、资产或持仓身份。
     * @param sequence 当前复制命令的 Core 序号
     * @param userId 要撤单的订单所属用户编号
     * @param orderId 要撤销的订单编号
     * @param commandId 本代复制命令 UUID，用于校验 Matcher 证据归属
     * @param shard 本命令固定的 Matcher 分片编号
     * @param identities 当前运行时资产、币对与账户身份字典
     * @param timestamp 复制日志中的业务时间戳
     * @param position 复制日志位置，供账户事实和终态元数据使用
     * @param resultTarget 在事件租约内接收订单最终值的目标，可为空
     */
    public MatcherSettlementEvent prepareDirectCancellation(long sequence, long userId, long orderId,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, LaneOrderResultTarget resultTarget) {
        owner.assertOwner();
        if (userId <= 0 || orderId <= 0) throw new IllegalArgumentException("cancel order is missing");
        // 目标用户所属的账户 Lane 位图。
        long laneMask = owner.topology().accountLaneMask(userId);
        owner.ensureMatcherSettlementDispatchCapacity(laneMask);
        // 本次从对象池借用或新建的结算事件。
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        event.batchStorage(1).cancellationOrderIds[0] = orderId;
        // 撤单不依赖当前合约配置，也不需要为成交解析资产或构建持仓身份。
        event.prepareDirect(sequence, laneMask, timestamp, position, commandId, shard,
                owner, identities, 1, List.of(), resultTarget);
        event.cancellation(userId);
        return event;
    }

    /**
     * 把本分块的有效撤单编号复制进事件；只有最后一块携带最终 Lane 提交序号，前面的块仍应用事实但不推进最终提交水位。
     * @param sequence 当前复制命令的 Core 序号
     * @param finalChunk 是否为本批最后一个撤单分块，决定是否推进最终 Lane 提交水位
     * @param userId 要撤单的订单所属用户编号
     * @param orders 本次撤单的原语编号数组，只复制 count 项
     * @param count 本次结算的有效订单项数
     * @param commandId 本代复制命令 UUID，用于校验 Matcher 证据归属
     * @param shard 本命令固定的 Matcher 分片编号
     * @param identities 当前运行时资产、币对与账户身份字典
     * @param timestamp 复制日志中的业务时间戳
     * @param position 复制日志位置，供账户事实和终态元数据使用
     * @param resultTarget 在事件租约内接收订单最终值的目标，可为空
     */
    public MatcherSettlementEvent prepareDirectCancelBatch(long sequence, boolean finalChunk, long userId, long[] orders, int count,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities, long timestamp, long position,
            LaneOrderResultTarget resultTarget) {
        owner.assertOwner();
        if (count <= 0 || count > orders.length) throw new IllegalArgumentException("invalid cancel chunk");
        // 本撤单分块目标用户所属的账户 Lane 位图。
        long mask = owner.topology().accountLaneMask(userId);
        owner.ensureMatcherSettlementDispatchCapacity(mask);
        // 本次从对象池借用或新建的结算事件。
        MatcherSettlementEvent event = matcherSettlementEventPool.pollFirst();
        if (event == null) event = new MatcherSettlementEvent();
        System.arraycopy(orders, 0, event.batchStorage(count).cancellationOrderIds, 0, count);
        event.prepareDirect(sequence, finalChunk ? sequence : 0, mask, timestamp, position, commandId, shard,
                owner, identities, count, List.of(), resultTarget);
        event.cancellation(userId);
        return event;
    }

    /**
     * 下单步骤 4.3：单 Matcher 发布方校验事件与实际路由，向每个 Lane 的分片 SPSC 队列投递一次；FUSED 在当前线程内联执行。已投递不代表账户或财务结算完成。
     * @param event 仍由当前命令租约持有的结算事件，空值按具体入口约定处理
     */
    void publishMatcherOwned(MatcherSettlementEvent event) {
        if (event == null || event.runtime() != owner || !event.direct()
                || !event.dispatched() || !event.resultPrepared())
            throw new IllegalStateException("invalid Matcher-owned settlement publication");
        if (event.dispatched() && event.queuedLaneMask() != 0)
            throw new IllegalStateException("Matcher settlement was already dispatched");
        // 本次仍需投递或合并余额边界的账户 Lane 位图。
        long lanes = event.routedLaneMask();
        if (lanes == 0) throw new IllegalStateException("Matcher settlement has no routed Lane");
        if (!owner.accountLanesStarted()) {
            if (owner.isFusedExecution()) {
                while (lanes != 0) {
                    // 当前位图最低有效位对应的账户 Lane 编号。
                    int laneId = Long.numberOfTrailingZeros(lanes);
                    lanes &= lanes - 1;
                    event.markLaneQueued(laneId);
                    event.execute(owner.accountLane(laneId));
                }
            }
            return;
        }
        // 事件固定的 Matcher 分片，用于选择每 Lane 的分片 SPSC 队列。
        int shard = event.directShard();
        while (lanes != 0) {
            // 当前位图最低有效位对应的账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            event.markLaneQueued(laneId);
            owner.laneWorkers[laneId].publishMatcherSettlement(shard, event);
        }
    }

    /** Owner 独占的结算事件复用池，只有消费者完成且变化已交出才入池。 */
    final java.util.ArrayDeque<MatcherSettlementEvent> matcherSettlementEventPool =
            new java.util.ArrayDeque<>();

    /**
     * Owner 清理已完成且已交出 changes 的事件并归还对象池；具体完成与租约校验由 event.clear 执行，空事件不处理。
     * @param event 仍由当前命令租约持有的结算事件，空值按具体入口约定处理
     */
    public void releaseMatcherSettlement(MatcherSettlementEvent event) {
        owner.assertOwner();
        if (event == null) return;
        event.clear();
        matcherSettlementEventPool.addFirst(event);
    }

    /**
     * Owner 丢弃已完成的拒绝等直达事件；若还持有 changes 先归还变化缓冲，再清理事件入池，未完成事件拒绝回收。
     * @param event 仍由当前命令租约持有的结算事件，空值按具体入口约定处理
     */
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

    /**
     * 在批量账户作用域中等待本项结算完成，先把余额前后值按顺序接入批次回滚边界，再交既有收集入口处理变化；资金接收参数继续受收集入口的账本所有权检查。
     * @param event 仍由当前命令租约持有的结算事件，空值按具体入口约定处理
     * @param funds 传给已有收集入口的资金累加器；账本持有资金时不得再接收重复资金事实
     * @param terminalOrderSink Owner 收集终态订单原语身份的接收者，可为空
     */
    public RuntimeTreasuryDelta collectOrderBatchMatcherSettlement(
            MatcherSettlementEvent event, RuntimeFundsAccumulator funds,
            TradingRuntimeState.TerminalOrderSink terminalOrderSink) {
        owner.assertOwner();
        if (!owner.orderBatchMutationScope() || !event.complete())
            throw new IllegalStateException("batch item settlement is not ready to collect");
        owner.assertAccountLanesHealthy();
        // 顺序批次共用同一资金边界，先把本项余额端点接入批次，再收集本项终态。
        // 本次仍需投递或合并余额边界的账户 Lane 位图。
        long lanes = event.requiredLaneMask();
        while (lanes != 0) {
            // 当前位图最低有效位对应的账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            owner.accountRollback().balancePatch(laneId).mergeSequential(event.changes().balancePatches[laneId]);
        }
        return owner.collectMatcherSettlement(event, event.hasLedgerSettlement() ? funds : null, terminalOrderSink);
    }

}
