package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.model.*;
import com.surprising.aeron.service.account.UserAccountSnapshot;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.OrderFundsReservation.Snapshot;
import com.surprising.aeron.service.snapshot.state.CoreStateHash;
import com.surprising.aeron.support.collection.StateMapSupport;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.TreeMap;

/** 单个产品线的冷状态视图，用于恢复、导出和明确边界上的业务核对。 */
public record TradingCoreState(
        ProductLine productLine,
        long revision,
        Map<Long, UserAccountSnapshot> users,
        Map<Long, CoreOrderState> orders,
        Map<String, CoreInstrument> instruments,
        CoreRiskState riskState,
        CoreTreasuryState treasuryState,
        Map<CoreLeverageKey, Long> leverages,
        Map<Long, CoreAlgoOrderState> algoOrders,
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers,
        Map<ClientOrderKey, Long> clientOrderIndex,
        Map<Long, CoreTriggerOrderState> triggerOrders) {

    public TradingCoreState {
        if (productLine == null || revision < 0 || users == null || orders == null
                || instruments == null || riskState == null || treasuryState == null || leverages == null
                || algoOrders == null || cancelAllAfterTimers == null
                || triggerOrders == null) {
            throw new IllegalArgumentException("invalid trading core state");
        }
        Map<Long, UserAccountSnapshot> sortedUsers = StateMapSupport.freezeSorted(users);
        Map<Long, CoreOrderState> sortedOrders = StateMapSupport.freezeSorted(orders);
        sortedUsers.forEach((userId, user) -> validateUser(productLine, userId, user));
        sortedOrders.forEach((orderId, order) -> validateOrder(productLine, sortedUsers, orderId, order));
        if (clientOrderIndex == null) {
            throw new IllegalArgumentException("client order index is required for an authoritative state transition");
        }
        Map<ClientOrderKey, Long> sortedClientOrders = StateMapSupport.freezeSorted(clientOrderIndex);
        int clientOrderCount = 0;
        for (CoreOrderState order : sortedOrders.values()) {
            if (order.clientOrderId().isEmpty()) continue;
            Long indexed = sortedClientOrders.get(new ClientOrderKey(order.userId(), order.clientOrderId()));
            if (indexed == null || indexed.longValue() != order.orderId()) {
                throw new IllegalArgumentException("client order index does not match authoritative orders");
            }
            clientOrderCount++;
        }
        if (clientOrderCount != sortedClientOrders.size()) {
            throw new IllegalArgumentException("client order index does not match authoritative orders");
        }
        users = sortedUsers;
        orders = sortedOrders;
        instruments = StateMapSupport.freezeSorted(instruments);
        leverages = StateMapSupport.freezeSorted(leverages);
        algoOrders = StateMapSupport.freezeSorted(algoOrders);
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> sortedTimers = StateMapSupport.freezeSorted(cancelAllAfterTimers);
        sortedTimers.forEach(TradingCoreState::validateTimer);
        cancelAllAfterTimers = sortedTimers;
        clientOrderIndex = sortedClientOrders;
        Map<Long, CoreTriggerOrderState> sortedTriggers = StateMapSupport.freezeSorted(triggerOrders);
        sortedTriggers.forEach((id, trigger) -> validateTrigger(productLine, sortedUsers, id, trigger));
        triggerOrders = sortedTriggers;
    }

    /** 构造带全部条件任务的冷状态，从订单推导客户端单号索引，再交主构造检查。 */
    public TradingCoreState(ProductLine productLine, long revision, Map<Long, UserAccountSnapshot> users,
                            Map<Long, CoreOrderState> orders,
                            Map<String, CoreInstrument> instruments, CoreRiskState riskState,
                            CoreTreasuryState treasuryState, Map<CoreLeverageKey, Long> leverages,
                            Map<Long, CoreAlgoOrderState> algoOrders,
                            Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers,
                            Map<Long, CoreTriggerOrderState> triggerOrders) {
        this(productLine, revision, users, orders, instruments, riskState, treasuryState,
                leverages, algoOrders, cancelAllAfterTimers, deriveClientOrderIndex(orders), triggerOrders);
    }

    /** 建立指定产品线、零修订且各业务表均为空的冷状态，产品线仍必须明确。 */
    public static TradingCoreState empty(ProductLine productLine) {
        return new TradingCoreState(productLine, 0, Map.of(), Map.of(),
                Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static void validateUser(ProductLine productLine, long userId, UserAccountSnapshot user) {
        if (userId != user.userId() || user.productLine() != productLine) {
            throw new IllegalArgumentException("user state belongs to another partition");
        }
    }

    /** 核对图键、订单自身 ID 及产品线，且订单用户必须已在本状态中。 */
    private static void validateOrder(ProductLine productLine, Map<Long, UserAccountSnapshot> users,
                                      long orderId, CoreOrderState order) {
        if (orderId != order.orderId() || order.productLine() != productLine
                || !users.containsKey(order.userId())) {
            throw new IllegalArgumentException("order state belongs to another partition");
        }
    }

    private static void validateTimer(CoreCancelAllAfterKey key, CoreCancelAllAfterState timer) {
        if (!key.equals(timer.key())) {
            throw new IllegalArgumentException("cancel-all-after key does not match authoritative timer");
        }
    }

    private static void validateTrigger(ProductLine productLine, Map<Long, UserAccountSnapshot> users,
                                         Object id, CoreTriggerOrderState trigger) {
        if (!id.equals(trigger.triggerOrderId()) || trigger.productLine() != productLine
                || !users.containsKey(trigger.userId())) {
            throw new IllegalArgumentException("trigger order state belongs to another partition");
        }
    }

    /** 按有序冷状态的固定选取字段混合产品线、修订、用户及未终态订单、杠杆、未终态算法、仍 open 的触发单、未终态强平、计时器、价格风险与扫描进度、财库余额及生命周期进度。 */
    public long businessStateHash() {
        long hash = CoreStateHash.mix(CoreStateHash.start(), com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(productLine));
        hash = CoreStateHash.mix(hash, revision);
        for (UserAccountSnapshot user : users.values()) {
            hash = hashUser(hash, user);
        }
        for (CoreOrderState order : orders.values()) {
            if (order.status().terminal()) continue;
            hash = hashOrder(hash, order);
        }
        for (Map.Entry<CoreLeverageKey, Long> entry : leverages.entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey().userId());
            hash = CoreStateHash.mix(hash, entry.getKey().instrumentId());
            hash = CoreStateHash.mix(hash, entry.getKey().marginMode().wireCode());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        for (CoreAlgoOrderState algo : algoOrders.values()) {
            if (algo.terminal()) continue;
            hash = CoreStateHash.mix(hash, algo.algoOrderId()); hash = CoreStateHash.mix(hash, algo.userId());
            hash = CoreStateHash.mix(hash, algo.instrumentId()); hash = CoreStateHash.mix(hash, algo.statusCode());
            hash = CoreStateHash.mix(hash, algo.updatedAtEpochMillis()); hash = CoreStateHash.mix(hash, algo.revision());
            if (algo.retiredExecutedQuantitySteps() != 0 || algo.pendingClosedChildId() != 0) {
                hash = CoreStateHash.mix(hash, "algo-retired-progress");
                hash = CoreStateHash.mix(hash, algo.retiredExecutedQuantitySteps());
                hash = CoreStateHash.mix(hash, algo.pendingClosedChildId());
            }
            for (long childOrderId : algo.childOrderIds()) hash = CoreStateHash.mix(hash, childOrderId);
        }
        for (CoreCancelAllAfterState timer : cancelAllAfterTimers.values()) {
            hash = CoreStateHash.mix(hash, timer.userId());
            hash = CoreStateHash.mix(hash, timer.symbolScope());
            hash = CoreStateHash.mix(hash, timer.status().wireCode());
            hash = CoreStateHash.mix(hash, timer.triggerAtEpochMillis());
            hash = CoreStateHash.mix(hash, timer.updatedAtEpochMillis());
            hash = CoreStateHash.mix(hash, timer.revision());
        }
        for (CoreTriggerOrderState trigger : triggerOrders.values()) {
            if (!trigger.status().open()) continue;
            hash = CoreStateHash.mix(hash, trigger.triggerOrderId());
            hash = CoreStateHash.mix(hash, trigger.userId());
            hash = CoreStateHash.mix(hash, trigger.instrumentId());
            hash = CoreStateHash.mix(hash, trigger.side().wireCode());
            hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(trigger.triggerType()));
            hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(trigger.triggerCondition()));
            hash = CoreStateHash.mix(hash, trigger.triggerPriceTicks());
            hash = CoreStateHash.mix(hash, trigger.highestPriceTicks());
            hash = CoreStateHash.mix(hash, trigger.lowestPriceTicks());
            hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(trigger.status()));
            hash = CoreStateHash.mix(hash, trigger.placedOrderId());
            hash = CoreStateHash.mix(hash, trigger.triggerSequence());
            hash = CoreStateHash.mix(hash, trigger.triggeredPriceTicks());
            hash = CoreStateHash.mix(hash, trigger.updatedAtEpochMillis());
            hash = CoreStateHash.mix(hash, trigger.revision());
            if (trigger.priceSource() != com.surprising.aeron.protocol.CoreTriggerPriceSource.MARK) {
                hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(trigger.priceSource()));
            }
        }
        for (CoreMarkPriceState mark : riskState.markPrices().values()) {
            hash = CoreStateHash.mix(hash, mark.instrumentId());
            hash = CoreStateHash.mix(hash, mark.markPriceTicks());
            hash = CoreStateHash.mix(hash, mark.indexPriceTicks());
            if (productLine.isOptionProduct()) hash = CoreStateHash.mix(hash, mark.forwardPriceTicks());
            hash = CoreStateHash.mix(hash, mark.priceSequence());
            hash = CoreStateHash.mix(hash, mark.generatedAtEpochMillis());
            if (mark.lastPriceTicks() > 0) hash = CoreStateHash.mix(hash, mark.lastPriceTicks());
        }
        for (CoreRiskSnapshot risk : riskState.snapshots().values()) {
            hash = CoreStateHash.mix(hash, risk.userId());
            hash = CoreStateHash.mix(hash, risk.instrumentId());
            hash = CoreStateHash.mix(hash, risk.positionSide().wireCode());
            hash = CoreStateHash.mix(hash, risk.priceSequence());
            hash = CoreStateHash.mix(hash, risk.equityUnits());
            hash = CoreStateHash.mix(hash, risk.unrealizedPnlUnits());
            hash = CoreStateHash.mix(hash, risk.maintenanceMarginUnits());
            hash = CoreStateHash.mix(hash, risk.marginRatioPpm());
            hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(risk.status()));
        }
        for (CoreLiquidationState liquidation : riskState.liquidations().values()) {
            if (liquidation.terminal()) continue;
            hash = CoreStateHash.mix(hash, liquidation.liquidationId());
            hash = CoreStateHash.mix(hash, liquidation.userId());
            hash = CoreStateHash.mix(hash, liquidation.instrumentId());
            hash = CoreStateHash.mix(hash, liquidation.marginMode().wireCode());
            hash = CoreStateHash.mix(hash, liquidation.positionSide().wireCode());
            hash = CoreStateHash.mix(hash, liquidation.triggerPriceSequence());
            hash = CoreStateHash.mix(hash, liquidation.signedQuantitySteps());
            hash = CoreStateHash.mix(hash, liquidation.closeQuantitySteps());
            hash = CoreStateHash.mix(hash, liquidation.deficitUnits());
            hash = CoreStateHash.mix(hash, liquidation.executionPriceTicks());
            hash = CoreStateHash.mix(hash, liquidation.liquidationFeeRatePpm());
            hash = CoreStateHash.mix(hash, liquidation.liquidationFeeUnits());
            hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(liquidation.status()));
            hash = CoreStateHash.mix(hash, liquidation.nextCancelOrderId());
        }
        for (CoreRiskState.RiskScan scan : riskState.scans().values()) {
            hash = CoreStateHash.mix(hash, scan.instrumentId());
            hash = CoreStateHash.mix(hash, scan.accountLaneId());
            hash = CoreStateHash.mix(hash, scan.priceSequence());
            hash = CoreStateHash.mix(hash, scan.scanStartPriceSequence());
            hash = CoreStateHash.mix(hash, scan.riskComplete());
            hash = CoreStateHash.mix(hash, scan.triggerComplete());
            hash = CoreStateHash.mix(hash, scan.triggerPhase());
            hash = CoreStateHash.mix(hash, scan.triggerPriceCursor());
            hash = CoreStateHash.mix(hash, scan.triggerOrderCursor());
            hash = CoreStateHash.mix(hash, scan.triggerUpperId());
            hash = CoreStateHash.mix(hash, scan.triggerMarkPriceTicks());
            hash = CoreStateHash.mix(hash, scan.triggerGeneratedAtEpochMillis());
            hash = CoreStateHash.mix(hash, scan.triggerOcoOrderId());
            hash = CoreStateHash.mix(hash, scan.triggerOcoCursor());
            hash = CoreStateHash.mix(hash, scan.lastScheduledRevision());
            hash = CoreStateHash.mix(hash, scan.laneProgress().size());
            for (RiskLaneProgress lane : scan.laneProgress()) {
                hash = CoreStateHash.mix(hash, lane.lastUserId());
                hash = CoreStateHash.mix(hash, lane.complete());
                hash = CoreStateHash.mix(hash, lane.userId());
                hash = CoreStateHash.mix(hash, lane.phase());
                hash = CoreStateHash.mix(hash, lane.positionCursor());
                hash = CoreStateHash.mix(hash, lane.reservationCursor());
                hash = CoreStateHash.mix(hash, lane.unrealizedPnlUnits());
                hash = CoreStateHash.mix(hash, lane.maintenanceMarginUnits());
                hash = CoreStateHash.mix(hash, lane.isolatedMarginUnits());
                hash = CoreStateHash.mix(hash, lane.isolatedReservationUnits());
                hash = CoreStateHash.mix(hash, lane.userRevision());
                hash = CoreStateHash.mix(hash, lane.marketRevision());
            }
        }
        hash = CoreStateHash.mix(hash, riskState.nextLiquidationId());
        hash = CoreStateHash.mix(hash, riskState.marketRevision());
        var scanControl = riskState.scanControl();
        hash = CoreStateHash.mix(hash, scanControl.version());
        hash = CoreStateHash.mix(hash, scanControl.ruleName());
        hash = CoreStateHash.mix(hash, scanControl.enabled());
        hash = CoreStateHash.mix(hash, scanControl.scanDelayMs());
        hash = CoreStateHash.mix(hash, scanControl.scanBatchSize());
        hash = CoreStateHash.mix(hash, scanControl.updatedBy());
        hash = CoreStateHash.mix(hash, scanControl.reason());
        hash = CoreStateHash.mix(hash, scanControl.updatedAtEpochMillis());
        hash = CoreStateHash.mix(hash, "treasuryFee");
        for (Map.Entry<String, Long> entry : treasuryState.feeBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryInsurance");
        for (Map.Entry<String, Long> entry : treasuryState.insuranceBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryDeficit");
        for (Map.Entry<String, Long> entry : treasuryState.insuranceDeficits().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryLiquidationFee");
        for (Map.Entry<String, Long> entry : treasuryState.liquidationFeeBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryFundingResidual");
        for (Map.Entry<String, Long> entry : treasuryState.fundingResidualBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryRoundingResidual");
        for (Map.Entry<String, Long> entry : treasuryState.roundingResidualBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryClearingPnl");
        for (Map.Entry<String, Long> entry : treasuryState.clearingPnlBalances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryFundingSettlement");
        for (Map.Entry<String, Long> entry : treasuryState.fundingSettlements().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryFundingProgress");
        for (Map.Entry<String, CoreTreasuryState.FundingProgress> entry : treasuryState.fundingProgress().entrySet()) {
            CoreTreasuryState.FundingProgress progress = entry.getValue();
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, progress.settlementId());
            hash = CoreStateHash.mix(hash, progress.fundingRatePpm());
            hash = CoreStateHash.mix(hash, progress.markPriceTicks());
            hash = CoreStateHash.mix(hash, progress.nextCursorUserId());
        }
        hash = CoreStateHash.mix(hash, "treasuryLifecycleSettlement");
        for (Map.Entry<String, Long> entry : treasuryState.lifecycleSettlements().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        hash = CoreStateHash.mix(hash, "treasuryLifecycleProgress");
        for (Map.Entry<String, CoreTreasuryState.LifecycleProgress> entry
                : treasuryState.lifecycleProgress().entrySet()) {
            CoreTreasuryState.LifecycleProgress progress = entry.getValue();
            hash = CoreStateHash.mix(hash, progress.requiredInsuranceUnits());
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, progress.settlementId());
            hash = CoreStateHash.mix(hash, progress.settlementPriceTicks());
            hash = CoreStateHash.mix(hash, progress.ordersComplete());
            hash = CoreStateHash.mix(hash, progress.nextCursorOrderId());
            hash = CoreStateHash.mix(hash, progress.nextCursorUserId());
        }
        return hash;
    }

    /** 在既有哈希上混合用户身份、修订、持仓模式、余额、剩余预留非零的冻结及全部持仓字段。 */
    static long hashUser(long initial, UserAccountSnapshot user) {
        long hash = CoreStateHash.mix(initial, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(user.productLine()));
        hash = CoreStateHash.mix(hash, user.userId());
        hash = CoreStateHash.mix(hash, user.revision());
        hash = CoreStateHash.mix(hash, user.positionMode().wireCode());
        for (AssetBalance balance : user.balances().values()) {
            hash = CoreStateHash.mix(hash, balance.asset());
            hash = CoreStateHash.mix(hash, balance.availableUnits());
            hash = CoreStateHash.mix(hash, balance.lockedUnits());
        }
        for (Snapshot reservation : user.reservations().values()) {
            if (reservation.remainingUnits() == 0) continue;
            hash = CoreStateHash.mix(hash, reservation.orderId());
            hash = CoreStateHash.mix(hash, reservation.instrumentId());
            hash = CoreStateHash.mix(hash, reservation.kind().wireCode());
            hash = CoreStateHash.mix(hash, reservation.asset());
            hash = CoreStateHash.mix(hash, reservation.reservedUnits());
            hash = CoreStateHash.mix(hash, reservation.releasedUnits());
            hash = CoreStateHash.mix(hash, reservation.consumedUnits());
            hash = CoreStateHash.mix(hash, reservation.orderQuantitySteps());
        }
        for (CorePositionState position : user.positions().values()) {
            hash = CoreStateHash.mix(hash, position.instrumentId());
            hash = CoreStateHash.mix(hash, position.marginAsset());
            hash = CoreStateHash.mix(hash, position.marginMode().wireCode());
            hash = CoreStateHash.mix(hash, position.positionSide().wireCode());
            hash = CoreStateHash.mix(hash, position.signedQuantitySteps());
            hash = CoreStateHash.mix(hash, position.entryPriceTicks());
            hash = CoreStateHash.mix(hash, position.entryValueTicks());
            hash = CoreStateHash.mix(hash, position.realizedPnlUnits());
            hash = CoreStateHash.mix(hash, position.positionMarginUnits());
        }
        return hash;
    }

    /** 在既有哈希上按固定顺序混合订单业务字段、费用、成交价值高低位及时间、日志位置、状态修订。 */
    static long hashOrder(long initial, CoreOrderState order) {
        long hash = CoreStateHash.mix(initial, order.orderId());
        hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(order.productLine()));
        hash = CoreStateHash.mix(hash, order.userId());
        hash = CoreStateHash.mix(hash, order.instrumentId());
        hash = CoreStateHash.mix(hash, order.side().wireCode());
        hash = CoreStateHash.mix(hash, order.priceTicks());
        hash = CoreStateHash.mix(hash, order.matchingPriceTicks());
        hash = CoreStateHash.mix(hash, order.quantitySteps());
        hash = CoreStateHash.mix(hash, order.executedQuantitySteps());
        hash = CoreStateHash.mix(hash, order.remainingQuantitySteps());
        hash = CoreStateHash.mix(hash, order.reduceOnly());
        hash = CoreStateHash.mix(hash, order.marginMode().wireCode());
        hash = CoreStateHash.mix(hash, order.positionSide().wireCode());
        hash = CoreStateHash.mix(hash, order.orderType().wireCode());
        hash = CoreStateHash.mix(hash, order.timeInForce().wireCode());
        hash = CoreStateHash.mix(hash, order.postOnly());
        hash = CoreStateHash.mix(hash, order.clientOrderId());
        hash = CoreStateHash.mix(hash, order.commandId().getMostSignificantBits());
        hash = CoreStateHash.mix(hash, order.commandId().getLeastSignificantBits());
        hash = CoreStateHash.mix(hash, order.makerFeeRatePpm());
        hash = CoreStateHash.mix(hash, order.takerFeeRatePpm());
        hash = CoreStateHash.mix(hash, order.cumulativeFeeUnits());
        hash = CoreStateHash.mix(hash, order.executedValueHigh());
        hash = CoreStateHash.mix(hash, order.executedValueLow());
        hash = CoreStateHash.mix(hash, order.createdAtEpochMillis());
        hash = CoreStateHash.mix(hash, order.updatedAtEpochMillis());
        hash = CoreStateHash.mix(hash, order.clusterPosition());
        hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(order.status()));
        return CoreStateHash.mix(hash, order.revision());
    }

    private static Map<ClientOrderKey, Long> deriveClientOrderIndex(Map<Long, CoreOrderState> orders) {
        Map<ClientOrderKey, Long> index = new TreeMap<>();
        if (orders != null) {
            for (CoreOrderState order : orders.values()) {
                if (!order.clientOrderId().isEmpty()) {
                    ClientOrderKey key = new ClientOrderKey(order.userId(), order.clientOrderId());
                    if (index.put(key, order.orderId()) != null) {
                        throw new IllegalArgumentException("duplicate clientOrderId for user");
                    }
                }
            }
        }
        return index;
    }

    /** 客户端单号的用户范围排序键，避免不同用户相同单号互相覆盖。 */
    public record ClientOrderKey(long userId, String clientOrderId) implements Comparable<ClientOrderKey> {
        public ClientOrderKey {
            if (userId <= 0 || clientOrderId == null || clientOrderId.isBlank()) {
                throw new IllegalArgumentException("invalid client order key");
            }
        }

        /** 先按用户数字比较，同用户再按原客户端单号字典序比较。 */
        @Override
        public int compareTo(ClientOrderKey other) {
            int userComparison = Long.compare(userId, other.userId);
            return userComparison != 0 ? userComparison : clientOrderId.compareTo(other.clientOrderId);
        }
    }
}
