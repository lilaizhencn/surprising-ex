package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.TradingCoreState.ClientOrderKey;
import java.util.Map;

public final class RuntimeStateProjector {

    private RuntimeStateProjector() {
    }

    public static TradingRuntimeState project(TradingCoreState source, RuntimeIdentityRegistry identities) {
        return project(source, identities,
                LaneTopology.configured(Boolean.getBoolean("surprising.aeron.p10-characterization")));
    }

    public static TradingRuntimeState project(
            TradingCoreState source, RuntimeIdentityRegistry identities, LaneTopology topology) {
        if (source == null || identities == null) throw new IllegalArgumentException("source and identities are required");
        TradingRuntimeState runtime = new TradingRuntimeState(topology);
        runtime.replaceAuxiliaryState(source);
        source.instruments().values().forEach(instrument -> {
            identities.symbolId(instrument.symbol());
            identities.assetId(instrument.baseAsset());
            identities.assetId(instrument.quoteAsset());
            identities.assetId(instrument.settleAsset());
        });
        source.users().forEach((userId, user) -> {
            runtime.putUser(new UserRuntime(user.productLine(), userId, user.revision(), user.positionMode()));
            user.balances().forEach((asset, balance) -> runtime.putBalance(new BalanceRuntime(
                    userId, identities.assetId(asset), balance.availableUnits(), balance.lockedUnits())));
            user.reservations().forEach((orderId, reservation) -> runtime.putReservation(toRuntimeReservation(
                    userId, reservation, identities)));
            user.positions().forEach((positionKey, position) -> runtime.putPosition(
                    identities.positionKey(userId, positionKey), new PositionRuntime(userId,
                            identities.symbolId(position.symbol()), identities.assetId(position.marginAsset()),
                            position.marginMode(), position.positionSide(), position.instrumentVersion(),
                            position.signedQuantitySteps(), position.entryPriceTicks(), position.entryValueTicks(),
                            position.realizedPnlUnits(), position.positionMarginUnits())));
        });
        source.treasuryState().feeBalances().forEach((asset, units) ->
                runtime.treasury().setFee(identities.assetId(asset), units));
        source.treasuryState().insuranceBalances().forEach((asset, units) ->
                runtime.treasury().setInsurance(identities.assetId(asset), units,
                        source.treasuryState().insuranceDeficits().getOrDefault(asset, 0L)));
        source.treasuryState().insuranceDeficits().forEach((asset, units) -> {
            if (!source.treasuryState().insuranceBalances().containsKey(asset)) {
                runtime.treasury().setInsurance(identities.assetId(asset), 0, units);
            }
        });
        source.treasuryState().liquidationFeeBalances().forEach((asset, units) ->
                runtime.treasury().setLiquidationFee(identities.assetId(asset), units));
        source.treasuryState().fundingResidualBalances().forEach((asset, units) ->
                runtime.treasury().setFundingResidual(identities.assetId(asset), units));
        source.treasuryState().roundingResidualBalances().forEach((asset, units) ->
                runtime.treasury().setRoundingResidual(identities.assetId(asset), units));
        source.treasuryState().clearingPnlBalances().forEach((asset, units) ->
                runtime.treasury().setClearingPnl(identities.assetId(asset), units));
        source.treasuryState().fundingSettlements().forEach((symbol, settlementId) ->
                runtime.treasury().setFundingSettlement(identities.symbolId(symbol), settlementId));
        source.treasuryState().fundingProgress().forEach((symbol, progress) ->
                runtime.treasury().setFundingProgress(identities.symbolId(symbol),
                        new TreasuryRuntime.FundingProgressRuntime(progress.settlementId(),
                                progress.instrumentVersion(), progress.fundingRatePpm(),
                                progress.accountLaneId(), progress.nextCursorUserId(), progress.commandId(),
                                progress.markPriceTicks(), progress.priceSequence())));
        source.treasuryState().lifecycleSettlements().forEach((symbol, settlementId) ->
                runtime.treasury().setLifecycleSettlement(identities.symbolId(symbol), settlementId));
        source.treasuryState().lifecycleProgress().forEach((symbol, progress) ->
                runtime.treasury().setLifecycleProgress(identities.symbolId(symbol),
                        new TreasuryRuntime.LifecycleProgressRuntime(progress.settlementId(),
                                progress.instrumentVersion(), progress.settlementPriceTicks(),
                                progress.optionCashUnitsPerContract(), progress.ordersComplete(),
                                progress.accountLaneId(), progress.nextCursorOrderId(),
                                progress.nextCursorUserId(), progress.commandId(), progress.requiredInsuranceUnits())));
        source.riskState().liquidations().forEach((liquidationId, liquidation) ->
                runtime.putLiquidation(new LiquidationRuntime(liquidationId, liquidation.userId(),
                        identities.symbolId(liquidation.symbol()), liquidation.marginMode(),
                        liquidation.positionSide(), liquidation.instrumentVersion(),
                        liquidation.triggerPriceSequence(), liquidation.signedQuantitySteps(),
                        liquidation.closeQuantitySteps(), liquidation.deficitUnits(),
                        liquidation.executionPriceTicks(), liquidation.liquidationFeeRatePpm(),
                        liquidation.liquidationFeeUnits(), liquidation.status(),
                        liquidation.nextCancelOrderId())));
        source.riskState().markPrices().forEach((symbol, mark) -> runtime.putMarkPrice(new MarkPriceRuntime(
                identities.symbolId(symbol), mark.instrumentVersion(), mark.markPriceTicks(),
                mark.indexPriceTicks(), mark.forwardPriceTicks(), mark.priceSequence(),
                mark.generatedAtEpochMillis())));
        source.riskState().snapshots().forEach((key, risk) -> runtime.putRiskSnapshot(
                identities.positionKey(risk.userId(), positionKey(risk.symbol(), risk.positionSide())),
                new RiskSnapshotRuntime(risk.userId(), identities.symbolId(risk.symbol()), risk.positionSide(),
                        risk.priceSequence(), risk.equityUnits(), risk.unrealizedPnlUnits(),
                        risk.maintenanceMarginUnits(), risk.marginRatioPpm(), risk.status())));
        source.riskState().scans().forEach((symbol, scan) -> runtime.putRiskScan(new RiskScanRuntime(
                identities.symbolId(symbol), scan.accountLaneId(), scan.priceSequence(),
                scan.scanStartPriceSequence(), scan.lastUserId(),
                scan.riskComplete(), scan.riskUserId(), scan.riskPhase(), scan.riskPositionCursor(),
                scan.riskReservationCursor(), scan.riskUnrealizedPnlUnits(), scan.riskMaintenanceMarginUnits(),
                scan.riskIsolatedMarginUnits(), scan.riskIsolatedReservationUnits(), scan.triggerComplete(),
                scan.triggerPhase(), scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                scan.triggerOcoCursor())));
        runtime.setNextLiquidationId(source.riskState().nextLiquidationId());
        source.orders().forEach((orderId, order) -> {
            runtime.putOrder(toRuntimeOrder(order, identities));
            if (source.productLine().supportsUserPositionMarginFlow()) {
                identities.positionKey(order.userId(), positionKey(order.symbol(), order.positionSide()));
            }
            if (!order.clientOrderId().isEmpty()) {
                runtime.putClientOrder(order.userId(), identities.clientKey(order.userId(), order.clientOrderId()), orderId);
            }
        });
        validateClientIndex(source, runtime, identities);
        runtime.rebuildAccountLaneHashes();
        runtime.clearChangedKeys();
        runtime.releaseOwnerForHandoff();
        identities.releaseOwnerForHandoff();
        return runtime;
    }

    private static void validateClientIndex(TradingCoreState source, TradingRuntimeState runtime,
                                             RuntimeIdentityRegistry identities) {
        for (Map.Entry<ClientOrderKey, Long> entry : source.clientOrderIndex().entrySet()) {
            ClientOrderKey key = entry.getKey();
            long clientKey = identities.clientKey(key.userId(), key.clientOrderId());
            Long projectedOrderId = runtime.orderIdByClient(key.userId(), clientKey);
            if (!Long.valueOf(entry.getValue()).equals(projectedOrderId)) {
                throw new IllegalStateException("runtime client order projection mismatch: " + key);
            }
        }
    }

    static OrderRuntime toRuntimeOrder(CoreOrderState order, RuntimeIdentityRegistry identities) {
        return new OrderRuntime(order.orderId(), order.productLine(), order.userId(),
                identities.symbolId(order.symbol()), order.instrumentVersion(), order.side(), order.priceTicks(),
                order.matchingPriceTicks(),
                order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.reduceOnly(), order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.createdAtEpochMillis(),
                order.updatedAtEpochMillis(),
                order.clusterPosition(), order.status(), order.revision());
    }

    static ReservationRuntime toRuntimeReservation(long userId, OrderReservation reservation,
                                                    RuntimeIdentityRegistry identities) {
        return new ReservationRuntime(reservation.orderId(), userId, identities.symbolId(reservation.symbol()),
                reservation.instrumentVersion(), reservation.kind(), identities.assetId(reservation.asset()),
                reservation.reservedUnits(), reservation.releasedUnits(), reservation.consumedUnits(),
                reservation.orderQuantitySteps());
    }

    private static String positionKey(String symbol, com.surprising.aeron.protocol.CorePositionSide side) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        return side.hedgeSide() ? normalized + ':' + side.name() : normalized;
    }
}
