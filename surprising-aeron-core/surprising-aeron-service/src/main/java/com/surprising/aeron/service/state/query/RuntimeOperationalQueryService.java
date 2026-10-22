package com.surprising.aeron.service.state.query;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreCancelAllAfterView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreTreasuryAssetView;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.ledger.TreasuryRuntime;
import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterKey;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.aeron.service.state.order.OrderRuntime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** 账本资产、资金费及到期结算进度、条件单、算法母单和定时撤单的查询视图组装。各入口按自己的扫描、条数或分页规则限制规模，读取时仍须遵守账户 Lane 与账本的所有权。 构造约定：只提供查询视图转换，不额外持有资金或订单权威状态。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeOperationalQueryService {

    /** 普通查询物化实体的统一条数上限一万，具体入口还可能接受更小的分页限制。 */
    public static final int MAX_QUERY_ENTITIES = 10_000;
    /** 单次查询允许扫描的索引规模上限 16384，避免结果少但遍历范围无限大。 */
    public static final int MAX_INDEX_SCAN = 16_384;

    /**
     * 通过运行时的账本静止读取入口获取资产视图，再调用账本数据版本的组装方法。
     * @param runtime 本次查询的运行时，调用方负责在正确账户 Lane 或账本读取范围内调用
     * @param identities 资产及币对整数编号到外部业务代码的映射
     */
    public static List<CoreTreasuryAssetView> treasuryAssets(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        return runtime.queryLedgerAtRest(/* 运行时静止读取入口传入的当前资金总账，按它组装资产视图。 */ ledger -> treasuryAssets(ledger, identities));
    }

    /**
     * 先检查总账项规模，再合并手续费、保险、亏空及各类残差中的资产编号，读取每种资产各项余额并按资产代码排序，返回只读列表。
     * @param treasury 当前允许读取的资金总账，包含各资产的手续费、保险和残差记录
     * @param identities 资产及币对整数编号到外部业务代码的映射
     */
    public static List<CoreTreasuryAssetView> treasuryAssets(TreasuryRuntime treasury, RuntimeIdentityRegistry identities) {
        if (treasury.assetLedgerEntryCount() > MAX_QUERY_ENTITIES) throw new QueryTooLargeException();
        // 从各类资金项中去重合并的资产整数编号集合。
        var ids = new org.eclipse.collections.impl.set.mutable.primitive.IntHashSet();
        ids.addAll(treasury.feeBalances().keySet());
        ids.addAll(treasury.insuranceBalances().keySet());
        ids.addAll(treasury.insuranceDeficits().keySet());
        ids.addAll(treasury.liquidationFeeBalances().keySet());
        ids.addAll(treasury.fundingResidualBalances().keySet());
        ids.addAll(treasury.roundingResidualBalances().keySet());
        ids.addAll(treasury.clearingPnlBalances().keySet());
        if (ids.size() > MAX_QUERY_ENTITIES) throw new QueryTooLargeException();
        // 资产编号数组，先按整数排序，再读取对应各项余额。
        int[] sorted = ids.toArray();
        Arrays.sort(sorted);
        // 当前各资产的完整资金视图，返回前再按对外资产代码排序。
        ArrayList<CoreTreasuryAssetView> views = new ArrayList<>(sorted.length);
        for (int /* 当前需要读取手续费、保险、亏空及残差余额的资产整数编号。 */ assetId : sorted) {
            views.add(new CoreTreasuryAssetView(identities.asset(assetId), treasury.fee(assetId),
                    treasury.insurance(assetId), treasury.insuranceDeficit(assetId),
                    treasury.liquidationFee(assetId), treasury.fundingResidual(assetId),
                    treasury.roundingResidual(assetId), treasury.clearingPnl(assetId)));
        }
        views.sort(Comparator.comparing(CoreTreasuryAssetView::asset));
        return List.copyOf(views);
    }

    /**
     * 已知币对有进行中的资金费时返回结算编号和下一用户游标；没有进行中任务则返回最近结算编号和已完成标记，未知币对按无任务处理。
     * @param runtime 本次查询的运行时，调用方负责在正确账户 Lane 或账本读取范围内调用
     * @param identities 资产及币对整数编号到外部业务代码的映射
     * @param instrumentId 要查询进度的外部币对编号，条件单过滤中空值或空串表示不限定
     */
    public static CoreFundingProgressView fundingProgress(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, String instrumentId) {
        // 外部币对编号在身份表中的已注册整数编号，未注册时直接返回无进行中任务。
        Integer symbolId = identities.findSymbolId(instrumentId);
        if (symbolId == null) return new CoreFundingProgressView(0, true, 0, 0);
        // 当前运行时中的资金总账，进度记录由该总账维护。
        TreasuryRuntime treasury = runtime.treasury();
        // 当前币对尚未完成的资金费进度，存在时展示下一用户游标。
        TreasuryRuntime.FundingProgressRuntime progress = treasury.fundingProgress(symbolId);
        return progress == null
                ? new CoreFundingProgressView(treasury.fundingSettlement(symbolId), true, 0, 0)
                : new CoreFundingProgressView(progress.settlementId(), false, progress.nextCursorUserId(), 0);
    }

    /**
     * 读取到期结算的订单与用户继续游标、订单阶段完成标记及所需保险金额；没有进行中任务时展示最近结算编号为已完成，未知币对按无任务处理。
     * @param runtime 本次查询的运行时，调用方负责在正确账户 Lane 或账本读取范围内调用
     * @param identities 资产及币对整数编号到外部业务代码的映射
     * @param instrumentId 要查询进度的外部币对编号，条件单过滤中空值或空串表示不限定
     */
    public static CoreSettlementProgressView settlementProgress(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, String instrumentId) {
        // 外部币对编号在身份表中的已注册整数编号，未注册时直接返回无进行中任务。
        Integer symbolId = identities.findSymbolId(instrumentId);
        if (symbolId == null) return new CoreSettlementProgressView(0, true, true, 0, 0, 0, 0);
        // 当前运行时中的资金总账，进度记录由该总账维护。
        TreasuryRuntime treasury = runtime.treasury();
        // 当前币对尚未完成的到期结算进度，包含撤单、用户游标和保险需求。
        TreasuryRuntime.LifecycleProgressRuntime progress = treasury.lifecycleProgress(symbolId);
        return progress == null
                ? new CoreSettlementProgressView(treasury.lifecycleSettlement(symbolId), true, true, 0, 0, 0, 0)
                : new CoreSettlementProgressView(progress.settlementId(), false, progress.ordersComplete(),
                progress.nextCursorOrderId(), progress.nextCursorUserId(), 0, 0, progress.requiredInsuranceUnits());
    }

    /**
     * 按候选输入顺序过滤用户、指定单号、币对、状态及开放状态；非指定单号查询还限制 before 游标。遇空编号、扫描上限或已满足条数就结束，不在这里重排候选。
     * @param runtime 本次查询的运行时，调用方负责在正确账户 Lane 或账本读取范围内调用
     * @param candidateIds 已经按所需分页顺序准备的条件单候选编号，结果沿该顺序返回
     * @param userId 需要匹配的用户编号，0 表示不限定用户
     * @param instrumentId 要查询进度的外部币对编号，条件单过滤中空值或空串表示不限定
     * @param status 需要匹配的条件单状态，null 表示不限状态
     * @param triggerOrderId 需要精确查询的条件单编号，0 表示使用分页过滤
     * @param beforeTriggerOrderId 分页只接收严格小于该编号的条件单，精确单号查询时忽略
     * @param openOnly 是否只保留协议状态判定仍开放的条件单
     * @param limit 本次希望返回的最多条件单条数，调用方应先校验为合法正数
     */
    public static List<CoreTriggerOrderStateView> triggerOrders(
            TradingRuntimeState runtime, Iterable<Long> candidateIds, long userId, String instrumentId,
            com.surprising.aeron.protocol.CoreTriggerOrderStatus status, long triggerOrderId,
            long beforeTriggerOrderId, boolean openOnly, int limit) {
        // 本次入口逐项组装的协议查询视图，达到其结果上限就停止。
        ArrayList<CoreTriggerOrderStateView> result = new ArrayList<>(Math.min(limit, 256));
        // 本次已检查的非空候选数量，按入口规则超限时停止或抛响应过大异常。
        int scanned = 0;
        for (Long /* 本轮需要读取的条件单候选编号，空编号使这一段候选遍历结束。 */ id : candidateIds) {
            if (id == null || ++scanned > MAX_INDEX_SCAN) break;
            // 实际读取到的条件单记录，要通过用户、币对、状态及分页过滤才加入结果。
            CoreTriggerOrderState order = runtime.triggerOrder(id);
            if (order == null || userId != 0 && order.userId() != userId
                    || triggerOrderId != 0 && order.triggerOrderId() != triggerOrderId
                    || instrumentId != null && !instrumentId.isEmpty() && !order.instrumentId().equalsIgnoreCase(instrumentId)
                    || status != null && order.status() != status
                    || triggerOrderId == 0 && order.triggerOrderId() >= beforeTriggerOrderId
                    || openOnly && !order.status().open()) continue;
            result.add(order.view());
            if (result.size() == limit) break;
        }
        return List.copyOf(result);
    }

    /**
     * 在母单和累计子单扫描上限内，把退役子单成交量加上仍保留子单的成交量，统计有效子单剩余量和数量，再补入待处理关闭子单编号形成母单视图，最多返回一万条。
     * @param runtime 本次查询的运行时，调用方负责在正确账户 Lane 或账本读取范围内调用
     * @param algoIds 要查看的算法母单编号集合，空编号会跳过
     */
    public static List<CoreAlgoOrderView> algoOrders(TradingRuntimeState runtime, Iterable<Long> algoIds) {
        // 本次入口逐项组装的协议查询视图，达到其结果上限就停止。
        ArrayList<CoreAlgoOrderView> result = new ArrayList<>();
        // 本次已检查的非空候选数量，按入口规则超限时停止或抛响应过大异常。
        int scanned = 0;
        // 所有已读取母单累计涉及的子单编号数，单独检查扫描上限。
        int scannedChildren = 0;
        for (Long /* 本轮要读取的算法母单编号，记录已不存在时不生成视图。 */ algoId : algoIds) {
            if (algoId == null) continue;
            if (++scanned > MAX_INDEX_SCAN) throw new QueryTooLargeException();
            // 当前算法母单，提供切片计划、已退役成交进度及仍关联的子单编号。
            CoreAlgoOrderState state = runtime.algoOrder(algoId);
            if (state == null) continue;
            scannedChildren = Math.addExact(scannedChildren, state.childOrderIds().size());
            if (scannedChildren > MAX_INDEX_SCAN) throw new QueryTooLargeException();
            // 母单累计成交数量，先含退役子单进度，再加仍可读取子单的成交量。
            long executed = state.retiredExecutedQuantitySteps();
            // 母单仍处于 OPEN 的子单未成交量合计，单位为数量步数。
            long active = 0;
            // 仍有效的 OPEN 子单数量，与总历史子单数量区分。
            int activeCount = 0;
            for (long /* 母单当前关联的一笔子单编号，用来读取最新成交与剩余数量。 */ childOrderId : state.childOrderIds()) {
                // 子单当前订单状态；已退役且不存在时，其最终成交应已计入母单历史进度。
                OrderRuntime child = runtime.order(childOrderId);
                if (child == null) continue; // 子单退役前，其最终成交量已经累计到母单。
                executed = Math.addExact(executed, child.executedQuantitySteps());
                if (child.status() == CoreOrderStatus.OPEN) {
                    active = Math.addExact(active, child.remainingQuantitySteps());
                    activeCount++;
                }
            }
            // 对外展示的母单子单编号列表，必要时补上待处理的已关闭子单。
            List<Long> children = state.childOrderIds();
            if (state.pendingClosedChildId() != 0) {
                // 为补入待处理关闭子单而临时复制的编号列表，随后转成只读列表。
                var recovered = new ArrayList<Long>(children);
                recovered.add(state.pendingClosedChildId());
                children = List.copyOf(recovered);
            }
            result.add(new CoreAlgoOrderView(state.algoOrderId(), state.userId(), state.clientAlgoOrderId(),
                    state.instrumentId(), state.algoTypeCode(), state.side(), state.priceTicks(), state.quantitySteps(),
                    state.childQuantitySteps(), state.intervalSeconds(), state.durationSeconds(), state.marginMode(),
                    state.positionSide(), state.reduceOnly(), state.postOnly(), state.timeInForce(), state.statusCode(),
                    state.currentOrderId(), state.rejectReason(), state.startAtEpochMillis(),
                    state.nextSliceAtEpochMillis(), state.completedAtEpochMillis(), state.createdAtEpochMillis(),
                    state.updatedAtEpochMillis(), state.revision(), children, executed, active, activeCount));
            if (result.size() == MAX_QUERY_ENTITIES) break;
        }
        return List.copyOf(result);
    }

    /**
     * 沿给定定时器键读取仍存在的倒计时撤单记录，扫描超限时报错，结果达到一万条就停止并返回只读视图列表。
     * @param runtime 本次查询的运行时，调用方负责在正确账户 Lane 或账本读取范围内调用
     * @param keys 按用户和币对范围定位的定时撤单键集合，空键会跳过
     */
    public static List<CoreCancelAllAfterView> cancelAllAfter(
            TradingRuntimeState runtime, Iterable<CoreCancelAllAfterKey> keys) {
        // 本次入口逐项组装的协议查询视图，达到其结果上限就停止。
        ArrayList<CoreCancelAllAfterView> result = new ArrayList<>();
        // 本次已检查的非空候选数量，按入口规则超限时停止或抛响应过大异常。
        int scanned = 0;
        for (CoreCancelAllAfterKey /* 本轮需要读取的用户及币对范围定时器键。 */ key : keys) {
            if (key == null) continue;
            if (++scanned > MAX_INDEX_SCAN) throw new QueryTooLargeException();
            // 按该键找到的倒计时撤单状态，存在时转成协议视图。
            CoreCancelAllAfterState state = runtime.cancelAllAfterTimer(key);
            if (state != null) result.add(state.view());
            if (result.size() == MAX_QUERY_ENTITIES) break;
        }
        return List.copyOf(result);
    }

    /**
     * 查询扫描范围或物化规模超过允许上限时使用的内部异常，用来与记录不存在等正常空结果区分。
     */
    public static final class QueryTooLargeException extends IllegalStateException {
    }
}
