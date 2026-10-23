package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.model.TradingCoreState.ClientOrderKey;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.account.UserRuntime;
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
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.risk.*;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.aeron.service.risk.RiskScanRuntime;
import com.surprising.aeron.service.risk.RiskSnapshotRuntime;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 把已解码的交易状态装回可变运行时的恢复入口。按原身份表还原用户、余额、预留、持仓、财库、风险及订单，再创建并绑定已完成重建的业务索引，重建账户哈希；完成后清除本次装载变化并释放线程归属，交给后续运行线程接管。这里只恢复内存，不负责读取快照字节或启动 Cluster。 构造约定：工具类仅提供恢复转换，不创建实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeStateProjector {

    /**
     * 通过统一配置入口绑定独立运行的拓扑，随后按指定身份表恢复运行时。
     * @param source 已经过快照或上游业务校验的只读交易状态
     * @param identities 本次恢复使用并绑定账户 Lane 的身份表，不是在本方法内新建的副本
     */
    public static TradingAccountRuntime project(TradingCoreState source, RuntimeIdentityRegistry identities) {
        return project(source, identities,
                CoreConfiguration.standaloneLaneTopology());
    }

    /**
     * 按给定拓扑创建原权威数据的可变表示，先装载业务值和身份，再核对客户订单别名、重建索引与账户哈希，最后释放运行时和身份表的装载线程归属。缺少币对或别名不一致时抛错；这里不承诺失败后撤销已写入的外部身份表。
     * @param source 已经过快照或上游业务校验的只读交易状态
     * @param identities 本次恢复使用并绑定账户 Lane 的身份表，不是在本方法内新建的副本
     * @param topology 恢复后账户 Lane 的数量与用户路由拓扑
     */
    public static TradingAccountRuntime project(
            TradingCoreState source, RuntimeIdentityRegistry identities, LaneTopology topology) {
        return project(source, identities, topology, CoreRuntimeConfiguration.standalone());
    }

    public static TradingAccountRuntime project(TradingCoreState source, RuntimeIdentityRegistry identities,
            LaneTopology topology, CoreRuntimeConfiguration configuration) {
        if (source == null || identities == null) throw new IllegalArgumentException("source and identities are required");
        // 按给定拓扑新建的可变运行时，装载完成后才交接线程归属。
        TradingAccountRuntime runtime = new TradingAccountRuntime(topology, configuration);
        identities.bindClientLanes(runtime);
        runtime.replaceAuxiliaryState(source);
        source.instruments().values().forEach(/* 当前需要登记币对和三种资产身份的注册配置。 */ instrument -> {
            identities.symbolId(instrument.instrumentIdValue());
            identities.assetId(instrument.baseAsset());
            identities.assetId(instrument.quoteAsset());
            identities.assetId(instrument.settleAsset());
        });
        source.users().forEach((/* 当前被恢复用户的编号。 */ userId, /* 该用户原修订号、账户模式及余额持仓等只读数据。 */ user) -> {
            runtime.putUser(new UserRuntime(user.productLine(), userId, user.revision(), user.positionMode()));
            user.balances().forEach((/* 当前余额或财库条目的资产名称，需要转换为内部资产编号。 */ asset, /* 当前用户资产的可用和冻结余额。 */ balance) -> runtime.putBalance(new BalanceRuntime(
                    userId, identities.assetId(asset), balance.availableUnits(), balance.lockedUnits())));
            user.reservations().forEach((/* 当前订单或预留在旧状态中的订单编号。 */ orderId, /* 当前要恢复的订单资金预留值。 */ reservation) -> runtime.putReservation(toRuntimeReservation(
                    userId, reservation, identities)));
            user.positions().forEach((/* 旧状态中的持仓业务键，用来还原用户持仓身份。 */ positionKey, /* 当前需要还原数量、成本、盈亏及保证金的持仓值。 */ position) -> runtime.putPosition(
                    identities.positionKey(userId, positionKey), new PositionRuntime(userId,
                            identities.symbolId(position.instrumentId()), identities.assetId(position.marginAsset()),
                            position.marginMode(), position.positionSide(), requireInstrument(source, position.instrumentId()),
                            position.signedQuantitySteps(), position.entryPriceTicks(), position.entryValueTicks(),
                            position.realizedPnlUnits(), position.positionMarginUnits())));
        });
        source.treasuryState().feeBalances().forEach((/* 当前余额或财库条目的资产名称，需要转换为内部资产编号。 */ asset, /* 当前资产在对应财库子账中的有符号金额。 */ units) ->
                runtime.treasury().setFee(identities.assetId(asset), units));
        source.treasuryState().insuranceBalances().forEach((/* 当前余额或财库条目的资产名称，需要转换为内部资产编号。 */ asset, /* 当前资产在对应财库子账中的有符号金额。 */ units) ->
                runtime.treasury().setInsurance(identities.assetId(asset), units,
                        source.treasuryState().insuranceDeficits().getOrDefault(asset, 0L)));
        source.treasuryState().insuranceDeficits().forEach((/* 当前余额或财库条目的资产名称，需要转换为内部资产编号。 */ asset, /* 当前资产在对应财库子账中的有符号金额。 */ units) -> {
            if (!source.treasuryState().insuranceBalances().containsKey(asset)) {
                runtime.treasury().setInsurance(identities.assetId(asset), 0, units);
            }
        });
        source.treasuryState().liquidationFeeBalances().forEach((/* 当前余额或财库条目的资产名称，需要转换为内部资产编号。 */ asset, /* 当前资产在对应财库子账中的有符号金额。 */ units) ->
                runtime.treasury().setLiquidationFee(identities.assetId(asset), units));
        source.treasuryState().fundingResidualBalances().forEach((/* 当前余额或财库条目的资产名称，需要转换为内部资产编号。 */ asset, /* 当前资产在对应财库子账中的有符号金额。 */ units) ->
                runtime.treasury().setFundingResidual(identities.assetId(asset), units));
        source.treasuryState().roundingResidualBalances().forEach((/* 当前余额或财库条目的资产名称，需要转换为内部资产编号。 */ asset, /* 当前资产在对应财库子账中的有符号金额。 */ units) ->
                runtime.treasury().setRoundingResidual(identities.assetId(asset), units));
        source.treasuryState().clearingPnlBalances().forEach((/* 当前余额或财库条目的资产名称，需要转换为内部资产编号。 */ asset, /* 当前资产在对应财库子账中的有符号金额。 */ units) ->
                runtime.treasury().setClearingPnl(identities.assetId(asset), units));
        source.treasuryState().fundingSettlements().forEach((/* 当前结算进度、标记价或扫描记录所属币对名称。 */ instrumentId, /* 该币对已经完整完成的资金费或生命周期结算编号。 */ settlementId) ->
                runtime.treasury().setFundingSettlement(identities.symbolId(instrumentId), settlementId));
        source.treasuryState().fundingProgress().forEach((/* 当前结算进度、标记价或扫描记录所属币对名称。 */ instrumentId, /* 当前币对尚未完成的分页进度，连同固定计算参数及命令 UUID 恢复。 */ progress) ->
                runtime.treasury().setFundingProgress(identities.symbolId(instrumentId),
                        new TreasuryRuntime.FundingProgressRuntime(progress.settlementId(),
                                requireInstrument(source, instrumentId), progress.fundingRatePpm(),
                                progress.nextCursorUserId(), progress.markPriceTicks(), progress.priceSequence())));
        source.treasuryState().lifecycleSettlements().forEach((/* 当前结算进度、标记价或扫描记录所属币对名称。 */ instrumentId, /* 该币对已经完整完成的资金费或生命周期结算编号。 */ settlementId) ->
                runtime.treasury().setLifecycleSettlement(identities.symbolId(instrumentId), settlementId));
        source.treasuryState().lifecycleProgress().forEach((/* 当前结算进度、标记价或扫描记录所属币对名称。 */ instrumentId, /* 当前币对尚未完成的分页进度，连同固定计算参数及命令 UUID 恢复。 */ progress) ->
                runtime.treasury().setLifecycleProgress(identities.symbolId(instrumentId),
                        new TreasuryRuntime.LifecycleProgressRuntime(progress.settlementId(),
                                requireInstrument(source, instrumentId), progress.settlementPriceTicks(),
                                progress.ordersComplete(), progress.nextCursorOrderId(), progress.nextCursorUserId(),
                                progress.requiredInsuranceUnits())));
        source.riskState().liquidations().forEach((/* 当前强平业务记录的编号。 */ liquidationId, /* 需要恢复状态、亏空、费用及撤单游标的强平记录。 */ liquidation) ->
                runtime.putLiquidation(new LiquidationRuntime(liquidationId, liquidation.userId(),
                        identities.symbolId(liquidation.instrumentId()), liquidation.marginMode(),
                        liquidation.positionSide(), requireInstrument(source, liquidation.instrumentId()),
                        liquidation.triggerPriceSequence(), liquidation.signedQuantitySteps(),
                        liquidation.closeQuantitySteps(), liquidation.deficitUnits(),
                        liquidation.executionPriceTicks(), liquidation.liquidationFeeRatePpm(),
                        liquidation.liquidationFeeUnits(), liquidation.status(),
                        liquidation.nextCancelOrderId())));
        source.riskState().markPrices().forEach((/* 当前结算进度、标记价或扫描记录所属币对名称。 */ instrumentId, /* 该币对保存的标记价、其他参考价及价格序号。 */ mark) -> runtime.putMarkPrice(new MarkPriceRuntime(
                identities.symbolId(instrumentId), requireInstrument(source, instrumentId), mark.markPriceTicks(),
                mark.indexPriceTicks(), mark.forwardPriceTicks(), mark.priceSequence(),
                mark.generatedAtEpochMillis(), mark.lastPriceTicks())));
        source.riskState().snapshots().forEach((/* 旧风险快照的映射键；此处按快照内用户、币对及方向重新取得内部持仓身份，不读取该键。 */ key, /* 需要恢复权益、保证金与风险状态的风险快照。 */ risk) -> runtime.putRiskSnapshot(
                identities.positionKey(risk.userId(), positionKey(risk.instrumentId(), risk.positionSide())),
                new RiskSnapshotRuntime(risk.userId(), identities.symbolId(risk.instrumentId()), risk.positionSide(),
                        risk.priceSequence(), risk.equityUnits(), risk.unrealizedPnlUnits(),
                        risk.maintenanceMarginUnits(), risk.marginRatioPpm())));
        source.riskState().scans().forEach((/* 当前结算进度、标记价或扫描记录所属币对名称。 */ instrumentId, /* 需要恢复风险及触发扫描阶段、游标和已累计中间值的记录。 */ scan) -> runtime.putRiskScan(new RiskScanRuntime(
                identities.symbolId(instrumentId), scan.accountLaneId(), scan.priceSequence(),
                scan.scanStartPriceSequence(), scan.lastUserId(),
                scan.riskComplete(), scan.riskUserId(), scan.riskPhase(), scan.riskPositionCursor(),
                scan.riskReservationCursor(), scan.riskUnrealizedPnlUnits(), scan.riskMaintenanceMarginUnits(),
                scan.riskIsolatedMarginUnits(), scan.riskIsolatedReservationUnits(), scan.triggerComplete(),
                scan.triggerPhase(), scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                scan.triggerOcoCursor(), scan.lastScheduledRevision(), scan.laneProgress())));
        runtime.setNextLiquidationId(source.riskState().nextLiquidationId());
        runtime.setMarketRevision(source.riskState().marketRevision());
        source.orders().forEach((/* 当前订单或预留在旧状态中的订单编号。 */ orderId, /* 需要装回运行时并登记非空客户订单别名的订单值。 */ order) -> {
            runtime.putOrder(toRuntimeOrder(order, identities, requireInstrument(source, order.instrumentId())));
            if (!order.clientOrderId().isEmpty()) {
                runtime.putClientOrder(order.userId(), identities.clientKey(order.userId(), order.clientOrderId()), orderId);
            }
        });
        validateClientIndex(source, runtime, identities);
        // 按币对和账户 Lane 查持仓用户的可重建索引。
        PositionUserIndex positionUserIndex = new PositionUserIndex(source, identities, topology);
        // 按币对汇总未平仓数量的可重建索引。
        OpenInterestIndex openInterestIndex = new OpenInterestIndex(source);
        // 待触发订单及触发条件的可重建索引。
        TriggerOrderIndex triggerOrderIndex = new TriggerOrderIndex(source);
        // 算法订单查询与推进使用的可重建索引。
        AlgoOrderIndex algoOrderIndex = new AlgoOrderIndex(source);
        // 强平计划及后续处理记录的可重建索引。
        LiquidationIndex liquidationIndex = new LiquidationIndex(source);
        // 倒计时撤单的可重建索引。
        CancelAllAfterIndex cancelAllAfterIndex = new CancelAllAfterIndex(source);
        // 活动订单路由的可重建索引。
        ActiveOrderIndex activeOrderIndex = new ActiveOrderIndex(source, identities);
        // 供自动减仓查找持仓候选的可重建索引。
        AdlPositionIndex adlPositionIndex = new AdlPositionIndex(source, identities);
        // 各索引已在构造时完成恢复，组合后直接绑定原运行时。
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

    /**
     * 逐项检查旧状态已有的客户订单别名在新运行时中指向相同订单；不反向遍历新索引来证明没有多余别名。
     * @param source 已经过快照或上游业务校验的只读交易状态
     * @param runtime 正在构建且尚未交接线程归属的运行时
     * @param identities 本次恢复使用并绑定账户 Lane 的身份表，不是在本方法内新建的副本
     */
    private static void validateClientIndex(TradingCoreState source, TradingAccountRuntime runtime,
                                             RuntimeIdentityRegistry identities) {
        for (Map.Entry<ClientOrderKey, Long> /* 旧状态的一条客户订单别名映射。 */ entry : source.clientOrderIndex().entrySet()) {
            // 待核对的用户编号与客户订单号业务键。
            ClientOrderKey key = entry.getKey();
            // 按用户取得的客户订单号内部身份。
            long clientKey = identities.clientKey(key.userId(), key.clientOrderId());
            // 新运行时按相同客户别名查出的订单编号，缺失或不同均不允许恢复成功。
            Long projectedOrderId = runtime.orderIdByClient(key.userId(), clientKey);
            if (!Long.valueOf(entry.getValue()).equals(projectedOrderId)) {
                throw new IllegalStateException("runtime client order projection mismatch: " + key);
            }
        }
    }

    /**
     * 把只读订单的数量、状态、价格、费用、时间和成交值等字段完整装入新的 OrderRuntime，并关联已解析的币对与内部编号。
     * @param order 要转换的只读订单值
     * @param identities 本次恢复使用并绑定账户 Lane 的身份表，不是在本方法内新建的副本
     * @param instrument 订单币对对应的注册配置
     */
    public static OrderRuntime toRuntimeOrder(CoreOrderState order, RuntimeIdentityRegistry identities,
                                              CoreInstrument instrument) {
        return new OrderRuntime(order.orderId(), order.productLine(), order.userId(),
                identities.symbolId(order.instrumentId()), instrument, order.side(), order.priceTicks(),
                order.matchingPriceTicks(),
                order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.reduceOnly(), order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.executedValueHigh(), order.executedValueLow(), order.createdAtEpochMillis(),
                order.updatedAtEpochMillis(),
                order.clusterPosition(), order.status(), order.revision());
    }

    /**
     * 恢复订单预留的总额、已释放额和已消耗额；总额使用 OrderReservation.reservedUnits，不能误当作当前剩余额度。
     * @param userId 预留所属用户编号
     * @param reservation 包含预留总额及累计释放、消耗的只读值
     * @param identities 本次恢复使用并绑定账户 Lane 的身份表，不是在本方法内新建的副本
     */
    static ReservationRuntime toRuntimeReservation(long userId, OrderReservation reservation,
                                                    RuntimeIdentityRegistry identities) {
        return new ReservationRuntime(reservation.orderId(), userId, identities.symbolId(reservation.instrumentId()),
                reservation.kind(), identities.assetId(reservation.asset()),
                reservation.reservedUnits(), reservation.releasedUnits(), reservation.consumedUnits(),
                reservation.orderQuantitySteps());
    }

    /**
     * 规范化币对名；双向持仓追加 LONG 或 SHORT 后缀，净持仓直接使用币对名，供风险快照查找原持仓身份。
     * @param instrumentId 需要规范化或查询的币对名称
     * @param side 净持仓、多仓或空仓方向
     */
    private static String positionKey(String instrumentId, com.surprising.aeron.protocol.CorePositionSide side) {
        // 经过币对名规范化后的持仓键主体。
        String normalized = OrderReservation.requireInstrumentId(instrumentId);
        return side.hedgeSide() ? normalized + ':' + side.name() : normalized;
    }

    /**
     * 从旧状态按规范化币对名取得注册配置，缺失时终止恢复，避免产生没有配置依据的订单或持仓。
     * @param source 已经过快照或上游业务校验的只读交易状态
     * @param instrumentId 需要规范化或查询的币对名称
     */
    private static CoreInstrument requireInstrument(TradingCoreState source, String instrumentId) {
        // 从旧状态中找到的注册币对配置。
        CoreInstrument instrument = source.instruments().get(OrderReservation.requireInstrumentId(instrumentId));
        if (instrument == null) throw new IllegalStateException("instrument is not registered: " + instrumentId);
        return instrument;
    }
}
