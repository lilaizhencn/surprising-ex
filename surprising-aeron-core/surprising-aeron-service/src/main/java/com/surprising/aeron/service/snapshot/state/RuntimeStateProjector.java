package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.InstrumentId;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.service.snapshot.state.TradingCoreState.ClientOrderKey;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.*;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.core.index.AdlPositionIndex;
import com.surprising.aeron.service.core.index.AlgoOrderIndex;
import com.surprising.aeron.service.core.index.CancelAllAfterIndex;
import com.surprising.aeron.service.core.index.LiquidationIndex;
import com.surprising.aeron.service.core.index.OpenInterestIndex;
import com.surprising.aeron.service.core.index.PositionUserIndex;
import com.surprising.aeron.service.core.index.RuntimeFactIndexes;
import com.surprising.aeron.service.core.index.TriggerOrderIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.order.OrderFundsReservation.Snapshot;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.risk.*;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.risk.RiskMarginCalculator;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 把已解码的交易状态装回可变运行时的恢复入口。完成后清除本次装载变化并释放线程归属，交给后续运行线程接管。这里只恢复内存，不负责读取快照字节或启动 Cluster。构造约定：工具类仅提供恢复转换，不创建实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeStateProjector {

    /** 通过统一配置入口绑定独立运行的拓扑，随后按指定身份表恢复运行时。 */
    public static TradingAccountRuntime project(TradingCoreState source, RuntimeIdentityRegistry identities) {
        return project(source, identities,
                CoreConfiguration.standaloneLaneTopology());
    }

    /** 按给定拓扑创建原权威数据的可变表示，先装载业务值和身份，再核对客户订单别名、重建索引与账户哈希，最后释放运行时和身份表的装载线程归属。 */
    public static TradingAccountRuntime project(
            TradingCoreState source, RuntimeIdentityRegistry identities, LaneTopology topology) {
        return project(source, identities, topology, CoreRuntimeConfiguration.standalone());
    }

    public static TradingAccountRuntime project(TradingCoreState source, RuntimeIdentityRegistry identities,
            LaneTopology topology, CoreRuntimeConfiguration configuration) {
        if (source == null || identities == null) throw new IllegalArgumentException("source and identities are required");
        TradingAccountRuntime runtime = new TradingAccountRuntime(topology, configuration);
        identities.bindClientLanes(runtime);
        runtime.replaceAuxiliaryState(source);
        source.instruments().values().forEach(instrument -> {
            identities.symbolId(instrument.instrumentIdValue());
            identities.assetId(instrument.baseAsset());
            identities.assetId(instrument.quoteAsset());
            identities.assetId(instrument.settleAsset());
        });
        source.users().forEach((userId, user) -> {
            runtime.putUser(new UserAccount(user.productLine(), userId, user.revision(), user.positionMode()));
            user.balances().forEach((asset, balance) -> runtime.putBalance(new AccountBalance(
                    userId, identities.assetId(asset), balance.availableUnits(), balance.lockedUnits())));
            user.reservations().forEach((orderId, reservation) -> runtime.putReservation(toRuntimeReservation(
                    userId, reservation, identities)));
            user.positions().forEach((positionKey, position) -> runtime.putPosition(
                    identities.positionKey(userId, positionKey), new Position(userId,
                            identities.symbolId(position.instrumentId()), identities.assetId(position.marginAsset()),
                            position.marginMode(), position.positionSide(), requireInstrument(source, position.instrumentId()),
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
        source.treasuryState().fundingSettlements().forEach((instrumentId, settlementId) ->
                runtime.treasury().setFundingSettlement(identities.symbolId(instrumentId), settlementId));
        source.treasuryState().fundingProgress().forEach((instrumentId, progress) ->
                runtime.treasury().setFundingProgress(identities.symbolId(instrumentId),
                        new TreasuryRuntime.FundingProgressRuntime(progress.settlementId(),
                                requireInstrument(source, instrumentId), progress.fundingRatePpm(),
                                progress.nextCursorUserId(), progress.markPriceTicks())));
        source.treasuryState().lifecycleSettlements().forEach((instrumentId, settlementId) ->
                runtime.treasury().setLifecycleSettlement(identities.symbolId(instrumentId), settlementId));
        source.treasuryState().lifecycleProgress().forEach((instrumentId, progress) -> {
            requireInstrument(source, instrumentId);
            runtime.treasury().setLifecycleProgress(identities.symbolId(instrumentId),
                    new TreasuryRuntime.LifecycleProgressRuntime(progress.settlementId(),
                            progress.settlementPriceTicks(),
                            progress.ordersComplete(), progress.nextCursorOrderId(), progress.nextCursorUserId(),
                            progress.requiredInsuranceUnits()));
        });
        source.riskState().liquidations().forEach((liquidationId, liquidation) ->
                runtime.putLiquidation(new RiskScanner.LiquidationOrder(liquidationId, liquidation.userId(),
                        identities.symbolId(liquidation.instrumentId()), liquidation.marginMode(),
                        liquidation.positionSide(), requireInstrument(source, liquidation.instrumentId()),
                        liquidation.triggerPriceSequence(), liquidation.signedQuantitySteps(),
                        liquidation.closeQuantitySteps(), liquidation.deficitUnits(),
                        liquidation.executionPriceTicks(), liquidation.liquidationFeeRatePpm(),
                        liquidation.liquidationFeeUnits(), liquidation.status(),
                        liquidation.nextCancelOrderId())));
        source.riskState().markPrices().forEach((instrumentId, mark) -> runtime.putMarkPrice(new MarkPrice(
                identities.symbolId(instrumentId), requireInstrument(source, instrumentId), mark.markPriceTicks(),
                mark.indexPriceTicks(), mark.forwardPriceTicks(), mark.priceSequence(),
                mark.generatedAtEpochMillis(), mark.lastPriceTicks())));
        source.riskState().snapshots().forEach((key, risk) -> runtime.putRiskSnapshot(
                identities.positionKey(risk.userId(), positionKey(risk.instrumentId(), risk.positionSide())),
                new RiskMarginCalculator.Snapshot(risk.userId(), identities.symbolId(risk.instrumentId()), risk.positionSide(),
                        risk.priceSequence(), risk.equityUnits(), risk.unrealizedPnlUnits(),
                        risk.maintenanceMarginUnits(), risk.marginRatioPpm())));
        source.riskState().scans().forEach((instrumentId, scan) -> runtime.putRiskScan(new RiskScanner.State(
                identities.symbolId(instrumentId), scan.accountLaneId(), scan.priceSequence(),
                scan.scanStartPriceSequence(),
                scan.riskComplete(), scan.triggerComplete(),
                scan.triggerPhase(), scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                scan.triggerOcoCursor(), scan.lastScheduledRevision(), scan.laneProgress())));
        runtime.setNextLiquidationId(source.riskState().nextLiquidationId());
        runtime.setMarketRevision(source.riskState().marketRevision());
        source.orders().forEach((orderId, order) -> {
            runtime.putOrder(toRuntimeOrder(order, identities, requireInstrument(source, order.instrumentId())));
            if (!order.clientOrderId().isEmpty()) {
                runtime.putClientOrder(order.userId(), identities.clientKey(order.userId(), order.clientOrderId()), orderId);
            }
        });
        validateClientIndex(source, runtime, identities);
        PositionUserIndex positionUserIndex = new PositionUserIndex(source, topology);
        OpenInterestIndex openInterestIndex = new OpenInterestIndex(source);
        TriggerOrderIndex triggerOrderIndex = new TriggerOrderIndex(source);
        AlgoOrderIndex algoOrderIndex = new AlgoOrderIndex(source);
        LiquidationIndex liquidationIndex = new LiquidationIndex(source);
        CancelAllAfterIndex cancelAllAfterIndex = new CancelAllAfterIndex(source);
        ActiveOrderIndex activeOrderIndex = new ActiveOrderIndex(source);
        AdlPositionIndex adlPositionIndex = new AdlPositionIndex(source, identities);
        RuntimeFactIndexes factIndexes = new RuntimeFactIndexes(
                positionUserIndex, openInterestIndex, triggerOrderIndex, algoOrderIndex,
                liquidationIndex, cancelAllAfterIndex, activeOrderIndex, adlPositionIndex);
        runtime.bindFactIndexes(factIndexes, identities);
        runtime.rebuildAccountLaneHashes();
        runtime.clearChangedKeys();
        runtime.releaseOwnerForHandoff();
        identities.releaseOwnerForHandoff();
        return runtime;
    }

    private static void validateClientIndex(TradingCoreState source, TradingAccountRuntime runtime,
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

    /** 把只读订单的数量、状态、价格、费用、时间和成交值等字段完整装入新的 Order，并关联已解析的币对与内部编号。 */
    public static Order toRuntimeOrder(CoreOrderState order, RuntimeIdentityRegistry identities,
                                              CoreInstrument instrument) {
        return new Order(order.orderId(), order.productLine(), order.userId(),
                identities.symbolId(order.instrumentId()), instrument, order.side(),
                order.matchingPriceTicks(),
                order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.reduceOnly(), order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.executedValueHigh(), order.executedValueLow(), order.createdAtEpochMillis(),
                order.updatedAtEpochMillis(),
                order.clusterPosition(), order.status(), order.revision());
    }

    /** 恢复订单预留的总额、已释放额和已消耗额。总额使用 OrderFundsReservation.Snapshot.reservedUnits，不能误当作当前剩余额度。 */
    static OrderFundsReservation toRuntimeReservation(long userId, OrderFundsReservation.Snapshot reservation,
                                                    RuntimeIdentityRegistry identities) {
        return new OrderFundsReservation(reservation.orderId(), userId, identities.symbolId(reservation.instrumentId()),
                reservation.kind(), identities.assetId(reservation.asset()),
                reservation.reservedUnits(), reservation.releasedUnits(), reservation.consumedUnits(),
                reservation.orderQuantitySteps());
    }

    private static String positionKey(String instrumentId, com.surprising.aeron.protocol.CorePositionSide side) {
        String normalized = InstrumentId.requireValid(instrumentId);
        return side.hedgeSide() ? normalized + ':' + side.name() : normalized;
    }

    /** 从旧状态按规范化币对名取得注册配置，缺失时终止恢复，避免产生没有配置依据的订单或持仓。 */
    private static CoreInstrument requireInstrument(TradingCoreState source, String instrumentId) {
        CoreInstrument instrument = source.instruments().get(InstrumentId.requireValid(instrumentId));
        if (instrument == null) throw new IllegalStateException("instrument is not registered: " + instrumentId);
        return instrument;
    }
}
