package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreMarkPriceState;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.CoreRiskSnapshot;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.service.account.UserAccountSnapshot;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.order.OrderFundsReservation.Snapshot;
import com.surprising.aeron.service.risk.*;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.aeron.service.treasury.SystemFunds;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 在快照、查询和审计边界把运行时实体转回可导出的交易值。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SnapshotWriter {

    /** 在 Owner 上取得快照视图，过滤待完成预约，将其冻结金额移回可用余额并减去预约贡献的修订次数。调用方须保证快照所需的线程和一致性边界。 */
    public static TradingCoreState writeSnapshot(AccountBook accountBook, IdIndex identities) {
        if (accountBook == null || identities == null) throw new IllegalArgumentException("runtime and identities required");
        accountBook.assertOwner();
        var runtimeUsers = accountBook.usersForSnapshot();
        var runtimeBalances = accountBook.balancesForSnapshot();

        Map<Long, Map<Long, OrderFundsReservation.Snapshot>> reservationsByUser = new HashMap<>();

        accountBook.reservationsForSnapshot().forEachKeyValue((orderId, reservation) -> {

            if (accountBook.pendingReservations().pendingReservation(orderId, reservation.userId())) return;
            if (!runtimeUsers.containsKey(reservation.userId())) {
                throw new IllegalStateException("reservation owner is not registered: " + reservation.userId());
            }
            reservationsByUser.computeIfAbsent(reservation.userId(), ignored -> new HashMap<>())
                    .put(orderId, new OrderFundsReservation.Snapshot(orderId, identities.instrumentId(reservation.symbolId()),
                            reservation.kind(), identities.asset(reservation.assetId()),
                            reservation.totalReservedUnits(), reservation.releasedUnits(), reservation.consumedUnits(),
                            reservation.orderQuantitySteps()));
        });
        Map<Long, Map<String, CorePositionState>> positionsByUser = new HashMap<>();

        accountBook.positionsForSnapshot().forEachKeyValue((positionKey, position) -> {

            if (!runtimeUsers.containsKey(position.userId())) {
                throw new IllegalStateException("position owner is not registered: " + position.userId());
            }
            String key = identities.positionKey(position.userId(), positionKey);
            positionsByUser.computeIfAbsent(position.userId(), ignored -> new HashMap<>())
                    .put(key, new CorePositionState(identities.instrumentId(position.symbolId()),
                            identities.asset(position.assetId()), position.marginMode(), position.positionSide(),
                            position.signedQuantitySteps(), position.entryPriceTicks(),
                            position.entryValueTicks(), position.realizedPnlUnits(), position.positionMarginUnits()));
        });

        Map<Long, UserAccountSnapshot> users = new TreeMap<>();
        runtimeUsers.forEachKeyValue((userId, user) -> {
            var userBalances = runtimeBalances.get(userId);
            Map<String, AssetBalance> balances;
            if (userBalances == null || userBalances.isEmpty()) {
                balances = Map.of();
            } else {
                balances = new HashMap<>(userBalances.size());
                userBalances.forEachKeyValue((assetId, balance) -> {
                    String asset = identities.asset(assetId);
                    long pending = accountBook.pendingReservations().pendingReservedUnits(userId, assetId);
                    balances.put(asset, new AssetBalance(asset, Math.addExact(balance.availableUnits(), pending),
                            Math.subtractExact(balance.lockedUnits(), pending)));
                });
            }
            Map<Long, OrderFundsReservation.Snapshot> reservationValues = reservationsByUser.get(userId);
            Map<Long, OrderFundsReservation.Snapshot> reservations = reservationValues == null ? Map.of() : reservationValues;
            Map<String, CorePositionState> positionValues = positionsByUser.get(userId);
            Map<String, CorePositionState> positions = positionValues == null ? Map.of() : positionValues;
            users.put(userId, new UserAccountSnapshot(user.productLine(), userId,
                    Math.subtractExact(user.revision(), accountBook.pendingReservations().pendingReservationCount(userId)), balances,
                    reservations, positions, user.positionMode()));
        });

        Map<Long, CoreOrderState> orders = new TreeMap<>();
        accountBook.ordersForSnapshot().forEachKeyValue((orderId, order) -> {
            if (accountBook.pendingReservations().pendingReservation(orderId, order.userId())) return;
            orders.put(orderId,
                new CoreOrderState(orderId, order.productLine(), order.userId(), identities.instrumentId(order.symbolId()),
                        order.side(), order.matchingPriceTicks(),
                        order.quantitySteps(),
                        order.executedQuantitySteps(), order.reduceOnly(),
                        order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                        order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                        order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.executedValueHigh(), order.executedValueLow(), order.createdAtEpochMillis(),
                        order.updatedAtEpochMillis(),
                        order.clusterPosition(), order.status(), order.revision()));
        });

        Map<String, CoreMarkPriceState> marks = new TreeMap<>();
        accountBook.markPricesForSnapshot().forEachKeyValue((symbolId, mark) -> {
            String instrumentId = identities.instrumentId(symbolId);
            marks.put(instrumentId, new CoreMarkPriceState(instrumentId, mark.markPriceTicks(),
                    mark.indexPriceTicks(), mark.forwardPriceTicks(), mark.priceSequence(),
                    mark.generatedAtEpochMillis(), mark.lastPriceTicks()));
        });
        Map<String, CoreRiskSnapshot> riskSnapshots = new TreeMap<>();
        accountBook.riskSnapshotsForSnapshot().forEachKeyValue((positionKey, risk) -> {
            CoreRiskSnapshot snapshot = new CoreRiskSnapshot(risk.userId(), identities.instrumentId(risk.symbolId()),
                    risk.positionSide(), risk.priceSequence(), risk.equityUnits(), risk.unrealizedPnlUnits(),
                    risk.maintenanceMarginUnits(), risk.marginRatioPpm());
            riskSnapshots.put(snapshot.key(), snapshot);
        });
        Map<Long, CoreLiquidationState> liquidations = new TreeMap<>();
        accountBook.liquidationsForSnapshot().forEachKeyValue((id, value) -> liquidations.put(id,
                new CoreLiquidationState(id, value.userId(), identities.instrumentId(value.symbolId()), value.marginMode(),
                        value.positionSide(), value.triggerPriceSequence(),
                        value.signedQuantitySteps(), value.closeQuantitySteps(), value.deficitUnits(),
                        value.executionPriceTicks(), value.liquidationFeeRatePpm(), value.liquidationFeeUnits(),
                        value.status(), value.nextCancelOrderId())));
        Map<String, CoreRiskState.RiskScan> scans = new TreeMap<>();
        accountBook.riskScansForSnapshot().forEachKeyValue((symbolId, scan) -> {
            String instrumentId = identities.instrumentId(symbolId);
            scans.put(instrumentId, new CoreRiskState.RiskScan(instrumentId, scan.accountLaneId(), scan.priceSequence(),
                    scan.scanStartPriceSequence(), scan.riskComplete(), scan.triggerComplete(),
                    scan.triggerPhase(), scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                    scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                    scan.triggerOcoCursor(), scan.lastScheduledRevision(), scan.laneProgress()));
        });
        CoreRiskState risk = new CoreRiskState(marks, riskSnapshots, liquidations, scans,
                accountBook.nextLiquidationId(), accountBook.riskScanControl(), accountBook.marketRevision());

        CoreTreasuryState systemFunds = accountBook.systemFundsForQuery(identities);

        Map<TradingCoreState.ClientOrderKey, Long> clientIndex = new TreeMap<>();
        accountBook.clientOrderIndexForSnapshot().forEachKeyValue((userId, entries) ->
                entries.forEachKeyValue((clientKey, orderId) -> {
                    if (!accountBook.pendingReservations().pendingReservation(orderId, userId)) {
                        clientIndex.put(new TradingCoreState.ClientOrderKey(
                                userId, identities.clientOrderId(userId, clientKey)), orderId);
                    }
                }));
        return new TradingCoreState(accountBook.productLine(),
                Math.subtractExact(accountBook.revision(), accountBook.pendingReservations().pendingReservationCount()), users, orders,
                accountBook.instrumentsForRuntime(), risk, systemFunds,
                new TreeMap<>(accountBook.leveragesForRuntime()), new TreeMap<>(accountBook.algoOrdersForRuntime()),
                new TreeMap<>(accountBook.cancelAllAfterTimersForRuntime()), clientIndex,
                new TreeMap<>(accountBook.triggerOrdersForRuntime()));
    }

    /** 由财库唯一所有者线程遍历各子账和进度，把内部资产、币对编号解析为业务名称，构造 CoreTreasuryState。 */
    public static CoreTreasuryState writeSystemFunds(SystemFunds ledger, IdIndex identities) {
        Map<String, Long> fees = new TreeMap<>();
        ledger.feeBalances().forEachKeyValue((id, units) -> fees.put(identities.asset(id), units));
        Map<String, Long> insurance = new TreeMap<>();
        ledger.insuranceBalances().forEachKeyValue((id, units) -> insurance.put(identities.asset(id), units));
        Map<String, Long> deficits = new TreeMap<>();
        ledger.insuranceDeficits().forEachKeyValue((id, units) -> deficits.put(identities.asset(id), units));
        Map<String, Long> liquidationFees = new TreeMap<>();
        ledger.liquidationFeeBalances().forEachKeyValue((id, units) ->
                liquidationFees.put(identities.asset(id), units));
        Map<String, Long> fundingResiduals = new TreeMap<>();
        ledger.fundingResidualBalances().forEachKeyValue((id, units) ->
                fundingResiduals.put(identities.asset(id), units));
        Map<String, Long> roundingResiduals = new TreeMap<>();
        // 舍入剩余条目的内部资产编号。
        ledger.roundingResidualBalances().forEachKeyValue((id, units) ->
                roundingResiduals.put(identities.asset(id), units));
        Map<String, Long> clearingPnl = new TreeMap<>();
        ledger.clearingPnlBalances().forEachKeyValue((id, units) ->
                clearingPnl.put(identities.asset(id), units));
        Map<String, Long> funding = new TreeMap<>();
        ledger.fundingSettlements().forEachKeyValue((id, value) -> funding.put(identities.instrumentId(id), value));
        Map<String, Long> lifecycle = new TreeMap<>();
        ledger.lifecycleSettlements().forEachKeyValue((id, value) -> lifecycle.put(identities.instrumentId(id), value));
        Map<String, CoreTreasuryState.FundingProgress> fundingProgress = new TreeMap<>();
        ledger.fundingProgresses().forEachKeyValue((id, value) -> fundingProgress.put(
                identities.instrumentId(id), new CoreTreasuryState.FundingProgress(value.settlementId(),
                        value.fundingRatePpm(), value.nextCursorUserId(), value.markPriceTicks())));
        Map<String, CoreTreasuryState.LifecycleProgress> lifecycleProgress = new TreeMap<>();
        ledger.lifecycleProgresses().forEachKeyValue((id, value) -> lifecycleProgress.put(
                identities.instrumentId(id), new CoreTreasuryState.LifecycleProgress(value.settlementId(),
                        value.settlementPriceTicks(), value.ordersComplete(), value.nextCursorOrderId(),
                        value.nextCursorUserId(), value.requiredInsuranceUnits())));
        return new CoreTreasuryState(fees, insurance, deficits, liquidationFees,
                fundingResiduals, roundingResiduals, clearingPnl, funding, lifecycle,
                fundingProgress, lifecycleProgress);
    }

}
