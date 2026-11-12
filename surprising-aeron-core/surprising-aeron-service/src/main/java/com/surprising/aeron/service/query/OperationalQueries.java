package com.surprising.aeron.service.query;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.model.CoreCancelAllAfterKey;
import com.surprising.aeron.model.CoreCancelAllAfterState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreCancelAllAfterView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreTreasuryAssetView;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.treasury.SystemFunds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 账本资产、资金费及到期结算进度、条件单、算法母单和定时撤单的查询视图组装。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OperationalQueries {

    public static final int MAX_QUERY_ENTITIES = 10_000;
    /** 单次查询允许扫描的索引规模上限 16384，避免结果少但遍历范围无限大。 */
    public static final int MAX_INDEX_SCAN = 16_384;

    /** 通过运行时的账本静止读取入口获取资产视图，再调用账本数据版本的组装方法。 */
    public static List<CoreTreasuryAssetView> systemFundsAssets(
            AccountBook accountBook, IdIndex identities) {
        return accountBook.queryLedgerAtRest(ledger -> systemFundsAssets(ledger, identities));
    }

    /** 先检查总账项规模，再合并手续费、保险、亏空及各类残差中的资产编号，读取每种资产各项余额并按资产代码排序，返回只读列表。 */
    public static List<CoreTreasuryAssetView> systemFundsAssets(SystemFunds systemFunds, IdIndex identities) {
        if (systemFunds.assetLedgerEntryCount() > MAX_QUERY_ENTITIES) throw new QueryTooLargeException();
        var ids = new org.eclipse.collections.impl.set.mutable.primitive.IntHashSet();
        ids.addAll(systemFunds.feeBalances().keySet());
        ids.addAll(systemFunds.insuranceBalances().keySet());
        ids.addAll(systemFunds.insuranceDeficits().keySet());
        ids.addAll(systemFunds.liquidationFeeBalances().keySet());
        ids.addAll(systemFunds.fundingResidualBalances().keySet());
        ids.addAll(systemFunds.roundingResidualBalances().keySet());
        ids.addAll(systemFunds.clearingPnlBalances().keySet());
        if (ids.size() > MAX_QUERY_ENTITIES) throw new QueryTooLargeException();
        int[] sorted = ids.toArray();
        Arrays.sort(sorted);
        ArrayList<CoreTreasuryAssetView> views = new ArrayList<>(sorted.length);
        for (int assetId : sorted) {
            views.add(new CoreTreasuryAssetView(identities.asset(assetId), systemFunds.fee(assetId),
                    systemFunds.insurance(assetId), systemFunds.insuranceDeficit(assetId),
                    systemFunds.liquidationFee(assetId), systemFunds.fundingResidual(assetId),
                    systemFunds.roundingResidual(assetId), systemFunds.clearingPnl(assetId)));
        }
        views.sort(Comparator.comparing(CoreTreasuryAssetView::asset));
        return List.copyOf(views);
    }

    /** 已知币对有进行中的资金费时返回结算编号和下一用户游标。 */
    public static CoreFundingProgressView fundingProgress(
            AccountBook accountBook, IdIndex identities, String instrumentId) {
        Integer symbolId = identities.findSymbolId(instrumentId);
        if (symbolId == null) return new CoreFundingProgressView(0, true, 0, 0);
        SystemFunds systemFunds = accountBook.systemFunds();
        SystemFunds.FundingProgressRuntime progress = systemFunds.fundingProgress(symbolId);
        return progress == null
                ? new CoreFundingProgressView(systemFunds.fundingSettlement(symbolId), true, 0, 0)
                : new CoreFundingProgressView(progress.settlementId(), false, progress.nextCursorUserId(), 0);
    }

    /** 读取到期结算的订单与用户继续游标、订单阶段完成标记及所需保险金额。 */
    public static CoreSettlementProgressView settlementProgress(
            AccountBook accountBook, IdIndex identities, String instrumentId) {
        Integer symbolId = identities.findSymbolId(instrumentId);
        if (symbolId == null) return new CoreSettlementProgressView(0, true, true, 0, 0, 0, 0);
        SystemFunds systemFunds = accountBook.systemFunds();
        SystemFunds.LifecycleProgressRuntime progress = systemFunds.lifecycleProgress(symbolId);
        return progress == null
                ? new CoreSettlementProgressView(systemFunds.lifecycleSettlement(symbolId), true, true, 0, 0, 0, 0)
                : new CoreSettlementProgressView(progress.settlementId(), false, progress.ordersComplete(),
                progress.nextCursorOrderId(), progress.nextCursorUserId(), 0, 0, progress.requiredInsuranceUnits());
    }

    /** 查询一笔算法母单，在子单扫描上限内汇总退役及仍保留子单的成交量和活动数量。 */
    public static CoreAlgoOrderView algoOrder(AccountBook accountBook, long algoOrderId) {
        CoreAlgoOrderState state = accountBook.algoOrder(algoOrderId);
        if (state == null) return null;
        if (state.childOrderIds().size() > MAX_INDEX_SCAN) throw new QueryTooLargeException();
        long executed = state.retiredExecutedQuantitySteps();
        long active = 0;
        int activeCount = 0;
        for (long childOrderId : state.childOrderIds()) {
            Order child = accountBook.order(childOrderId);
            if (child == null) continue;
            executed = Math.addExact(executed, child.executedQuantitySteps());
            if (child.status() == CoreOrderStatus.OPEN) {
                active = Math.addExact(active, child.remainingQuantitySteps());
                activeCount++;
            }
        }
        return new CoreAlgoOrderView(state.algoOrderId(), state.userId(), state.clientAlgoOrderId(),
                state.instrumentId(), state.algoTypeCode(), state.side(), state.priceTicks(), state.quantitySteps(),
                state.childQuantitySteps(), state.intervalSeconds(), state.durationSeconds(), state.marginMode(),
                state.positionSide(), state.reduceOnly(), state.timeInForce(), state.statusCode(),
                state.currentOrderId(), state.rejectReason(), state.startAtEpochMillis(),
                state.nextSliceAtEpochMillis(), state.createdAtEpochMillis(),
                state.updatedAtEpochMillis(), state.revision(), state.childOrderIds(), executed, active,
                activeCount, state.pendingClosedChildId());
    }

    /** 沿给定定时器键读取仍存在的倒计时撤单记录，扫描超限时报错，结果达到一万条就停止并返回只读视图列表。 */
    public static List<CoreCancelAllAfterView> cancelAllAfter(
            AccountBook accountBook, Iterable<CoreCancelAllAfterKey> keys) {
        ArrayList<CoreCancelAllAfterView> result = new ArrayList<>();
        int scanned = 0;
        for (CoreCancelAllAfterKey key : keys) {
            if (key == null) continue;
            if (++scanned > MAX_INDEX_SCAN) throw new QueryTooLargeException();
            CoreCancelAllAfterState state = accountBook.cancelAllAfterTimer(key);
            if (state != null) result.add(state.view());
            if (result.size() == MAX_QUERY_ENTITIES) break;
        }
        return List.copyOf(result);
    }
}
