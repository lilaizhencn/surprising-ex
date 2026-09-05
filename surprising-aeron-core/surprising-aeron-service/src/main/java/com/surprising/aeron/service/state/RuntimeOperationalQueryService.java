package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreCancelAllAfterView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreTreasuryAssetView;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class RuntimeOperationalQueryService {

    public static final int MAX_QUERY_ENTITIES = 10_000;
    public static final int MAX_INDEX_SCAN = 16_384;
    private RuntimeOperationalQueryService() {
    }

    public static List<CoreTreasuryAssetView> treasuryAssets(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        TreasuryRuntime treasury = runtime.treasury();
        if (treasury.assetLedgerEntryCount() > MAX_QUERY_ENTITIES) throw new QueryTooLargeException();
        var ids = new org.eclipse.collections.impl.set.mutable.primitive.IntHashSet();
        ids.addAll(treasury.feeBalances().keySet());
        ids.addAll(treasury.insuranceBalances().keySet());
        ids.addAll(treasury.insuranceDeficits().keySet());
        ids.addAll(treasury.liquidationFeeBalances().keySet());
        ids.addAll(treasury.fundingResidualBalances().keySet());
        ids.addAll(treasury.roundingResidualBalances().keySet());
        ids.addAll(treasury.clearingPnlBalances().keySet());
        if (ids.size() > MAX_QUERY_ENTITIES) throw new QueryTooLargeException();
        int[] sorted = ids.toArray();
        Arrays.sort(sorted);
        ArrayList<CoreTreasuryAssetView> views = new ArrayList<>(sorted.length);
        for (int assetId : sorted) {
            views.add(new CoreTreasuryAssetView(identities.asset(assetId), treasury.fee(assetId),
                    treasury.insurance(assetId), treasury.insuranceDeficit(assetId),
                    treasury.liquidationFee(assetId), treasury.fundingResidual(assetId),
                    treasury.roundingResidual(assetId), treasury.clearingPnl(assetId)));
        }
        views.sort(Comparator.comparing(CoreTreasuryAssetView::asset));
        return List.copyOf(views);
    }

    public static CoreFundingProgressView fundingProgress(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, String symbol) {
        Integer symbolId = identities.findSymbolId(symbol);
        if (symbolId == null) return new CoreFundingProgressView(0, true, 0, 0);
        TreasuryRuntime treasury = runtime.treasury();
        TreasuryRuntime.FundingProgressRuntime progress = treasury.fundingProgress(symbolId);
        return progress == null
                ? new CoreFundingProgressView(treasury.fundingSettlement(symbolId), true, 0, 0)
                : new CoreFundingProgressView(progress.settlementId(), false, progress.nextCursorUserId(), 0);
    }

    public static CoreSettlementProgressView settlementProgress(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, String symbol) {
        Integer symbolId = identities.findSymbolId(symbol);
        if (symbolId == null) return new CoreSettlementProgressView(0, true, true, 0, 0, 0, 0);
        TreasuryRuntime treasury = runtime.treasury();
        TreasuryRuntime.LifecycleProgressRuntime progress = treasury.lifecycleProgress(symbolId);
        return progress == null
                ? new CoreSettlementProgressView(treasury.lifecycleSettlement(symbolId), true, true, 0, 0, 0, 0)
                : new CoreSettlementProgressView(progress.settlementId(), false, progress.ordersComplete(),
                progress.nextCursorOrderId(), progress.nextCursorUserId(), 0, 0);
    }

    public static List<CoreTriggerOrderStateView> triggerOrders(
            TradingRuntimeState runtime, Iterable<Long> candidateIds, long userId, String symbol,
            com.surprising.aeron.protocol.CoreTriggerOrderStatus status, long triggerOrderId,
            long beforeTriggerOrderId, boolean openOnly, int limit) {
        ArrayList<CoreTriggerOrderStateView> result = new ArrayList<>(Math.min(limit, 256));
        int scanned = 0;
        for (Long id : candidateIds) {
            if (id == null || ++scanned > MAX_INDEX_SCAN) break;
            CoreTriggerOrderState order = runtime.triggerOrder(id);
            if (order == null || userId != 0 && order.userId() != userId
                    || triggerOrderId != 0 && order.triggerOrderId() != triggerOrderId
                    || symbol != null && !symbol.isEmpty() && !order.symbol().equalsIgnoreCase(symbol)
                    || status != null && order.status() != status
                    || triggerOrderId == 0 && order.triggerOrderId() >= beforeTriggerOrderId
                    || openOnly && !order.status().open()) continue;
            result.add(order.view());
            if (result.size() == limit) break;
        }
        return List.copyOf(result);
    }

    public static List<CoreAlgoOrderView> algoOrders(TradingRuntimeState runtime, Iterable<Long> algoIds) {
        ArrayList<CoreAlgoOrderView> result = new ArrayList<>();
        int scanned = 0;
        int scannedChildren = 0;
        for (Long algoId : algoIds) {
            if (algoId == null) continue;
            if (++scanned > MAX_INDEX_SCAN) throw new QueryTooLargeException();
            CoreAlgoOrderState state = runtime.algoOrder(algoId);
            if (state == null) continue;
            scannedChildren = Math.addExact(scannedChildren, state.childOrderIds().size());
            if (scannedChildren > MAX_INDEX_SCAN) throw new QueryTooLargeException();
            long executed = 0;
            long active = 0;
            int activeCount = 0;
            for (long childOrderId : state.childOrderIds()) {
                OrderRuntime child = runtime.order(childOrderId);
                if (child == null) throw new IllegalStateException("algo child order missing");
                executed = Math.addExact(executed, child.executedQuantitySteps());
                if (child.status() == CoreOrderStatus.OPEN) {
                    active = Math.addExact(active, child.remainingQuantitySteps());
                    activeCount++;
                }
            }
            result.add(new CoreAlgoOrderView(state.algoOrderId(), state.userId(), state.clientAlgoOrderId(),
                    state.symbol(), state.algoTypeCode(), state.side(), state.priceTicks(), state.quantitySteps(),
                    state.childQuantitySteps(), state.intervalSeconds(), state.durationSeconds(), state.marginMode(),
                    state.positionSide(), state.reduceOnly(), state.postOnly(), state.timeInForce(), state.statusCode(),
                    state.currentOrderId(), state.rejectReason(), state.traceId(), state.startAtEpochMillis(),
                    state.nextSliceAtEpochMillis(), state.completedAtEpochMillis(), state.createdAtEpochMillis(),
                    state.updatedAtEpochMillis(), state.revision(), state.childOrderIds(), executed, active, activeCount));
            if (result.size() == MAX_QUERY_ENTITIES) break;
        }
        return List.copyOf(result);
    }

    public static List<CoreCancelAllAfterView> cancelAllAfter(
            TradingRuntimeState runtime, Iterable<CoreCancelAllAfterKey> keys) {
        ArrayList<CoreCancelAllAfterView> result = new ArrayList<>();
        int scanned = 0;
        for (CoreCancelAllAfterKey key : keys) {
            if (key == null) continue;
            if (++scanned > MAX_INDEX_SCAN) throw new QueryTooLargeException();
            CoreCancelAllAfterState state = runtime.cancelAllAfterTimer(key);
            if (state != null) result.add(state.view());
            if (result.size() == MAX_QUERY_ENTITIES) break;
        }
        return List.copyOf(result);
    }

    public static final class QueryTooLargeException extends IllegalStateException {
    }
}
