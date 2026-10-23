package com.surprising.aeron.service.core;

import com.surprising.aeron.service.snapshot.state.TradingRuntimeSnapshot;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 仅供测试把可变交易运行时投影为便于逐字段比较的 TradingRuntimeSnapshot。各类表转换为排序 Map 并保存当时字段，包含风控/触发续扫、资金费和到期结算进度；它不是生产快照协议，也不读取原生订单簿，不在每条业务命令中维护另一份权威状态。
 */
public final class RuntimeSnapshotBuilder {

    /**
     * 禁止实例化测试用静态投影工具。
     */
    private RuntimeSnapshotBuilder() {
    }

    /**
     * 要求当前线程是 Owner 且修订非负，把账户、订单、预留、持仓、强平、行情、续扫和财库状态转成排序测试值，再带上品种、杠杆、算法单、触发单及定时撤单表。订单投影只抄本辅助器列出的字段，未单独读取撮合保护价或成交金额高低位。只检查 Owner 身份，不自行建立或等待跨 Lane 快照屏障，调用方保证读取边界。
     * @param state 调用方已建立合法快照读取边界的可变运行时
     * @param revision 给测试投影指定的非负修订号
     */
    public static TradingRuntimeSnapshot capture(TradingAccountRuntime state, long revision) {
        state.assertOwner();
        if (revision < 0) {
            throw new IllegalArgumentException("snapshot revision must not be negative");
        }
        // 按用户号排序的用户身份、修订与持仓模式测试投影。
        Map<Long, TradingRuntimeSnapshot.UserSnapshot> users = new TreeMap<>();
        state.usersForSnapshot().forEachKeyValue((/* 当前账户表或客户单号索引对应的用户编号。 */ userId, /* 当前读取的运行时用户对象，只投影身份、修订与持仓模式。 */ user) ->
                users.put(userId, new TradingRuntimeSnapshot.UserSnapshot(user.productLine(), user.userId(),
                        user.revision(), user.positionMode())));

        // 以用户号和资产编号为键的可用/冻结余额投影。
        Map<TradingRuntimeSnapshot.BalanceKey, TradingRuntimeSnapshot.BalanceSnapshot> balances = new TreeMap<>();
        state.balancesForSnapshot().forEachKeyValue((/* 当前账户表或客户单号索引对应的用户编号。 */ userId, /* 当前用户按资产编号索引的运行时余额表。 */ userBalances) ->
                userBalances.forEachKeyValue((/* 当前余额或财库子账的整数资产编号。 */ assetId, /* 当前资产的可用与冻结余额值。 */ balance) -> balances.put(
                        new TradingRuntimeSnapshot.BalanceKey(userId, assetId),
                        new TradingRuntimeSnapshot.BalanceSnapshot(balance.availableUnits(), balance.lockedUnits()))));

        // 按订单号排序的订单字段投影，不复制原生订单簿 FIFO 链接。
        Map<Long, TradingRuntimeSnapshot.OrderSnapshot> orders = new TreeMap<>();
        state.ordersForSnapshot().forEachKeyValue((/* 当前订单、预留或客户端键映射关联的普通订单号。 */ orderId, /* 当前读取的运行时订单，保存身份、准入、费率、数量及提交元数据。 */ order) -> orders.put(orderId,
                new TradingRuntimeSnapshot.OrderSnapshot(order.productLine(), order.userId(), order.symbolId(),
                        order.side(), order.priceTicks(), order.quantitySteps(),
                        order.executedQuantitySteps(), order.remainingQuantitySteps(), order.reduceOnly(),
                        order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                        order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                        order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.createdAtEpochMillis(),
                        order.updatedAtEpochMillis(),
                        order.clusterPosition(), order.status(), order.revision())));

        // 按订单号排序的冻结预留总额、释放额和消费额投影。
        Map<Long, TradingRuntimeSnapshot.ReservationSnapshot> reservations = new TreeMap<>();
        state.reservationsForSnapshot().forEachKeyValue((/* 当前订单、预留或客户端键映射关联的普通订单号。 */ orderId, /* 当前订单的运行时预留，投影原有字段而不重新计算金额。 */ reservation) -> reservations.put(orderId,
                new TradingRuntimeSnapshot.ReservationSnapshot(reservation.userId(), reservation.symbolId(),
                        reservation.kind(), reservation.assetId(),
                        reservation.totalReservedUnits(), reservation.releasedUnits(), reservation.consumedUnits(),
                        reservation.orderQuantitySteps())));

        // 按用户与客户端键排序的订单号索引投影。
        Map<TradingRuntimeSnapshot.ClientOrderKey, Long> clientOrderIndex = new TreeMap<>();
        state.clientOrderIndexForSnapshot().forEachKeyValue((/* 当前账户表或客户单号索引对应的用户编号。 */ userId, /* 当前用户的客户端键到订单号索引。 */ userClientOrders) ->
                userClientOrders.forEachKeyValue((/* 当前用户的客户端订单键。 */ clientKey, /* 当前订单、预留或客户端键映射关联的普通订单号。 */ orderId) ->
                        clientOrderIndex.put(new TradingRuntimeSnapshot.ClientOrderKey(userId, clientKey), orderId)));

        // 按用户与原持仓键排序的持仓投影。
        Map<TradingRuntimeSnapshot.PositionKey, TradingRuntimeSnapshot.PositionSnapshot> positions = new TreeMap<>();
        state.positionsForSnapshot().forEachKeyValue((/* 运行时已有的持仓索引键，与用户号组合成测试键。 */ positionKey, /* 当前运行时持仓，投影数量、成本、盈亏及保证金字段。 */ position) -> positions.put(
                new TradingRuntimeSnapshot.PositionKey(position.userId(), positionKey),
                new TradingRuntimeSnapshot.PositionSnapshot(position.userId(), position.symbolId(), position.assetId(),
                        position.marginMode(), position.positionSide(),
                        position.signedQuantitySteps(), position.entryPriceTicks(), position.entryValueTicks(),
                        position.realizedPnlUnits(), position.positionMarginUnits())));

        // 按强平标识排序的执行进度投影。
        Map<Long, TradingRuntimeSnapshot.LiquidationSnapshot> liquidations = new TreeMap<>();
        state.liquidationsForSnapshot().forEachKeyValue((/* 当前强平任务标识。 */ liquidationId, /* 当前强平任务的持仓、费率、缺口和撤单游标等进度。 */ liquidation) -> liquidations.put(
                liquidationId, new TradingRuntimeSnapshot.LiquidationSnapshot(liquidation.userId(),
                        liquidation.symbolId(), liquidation.marginMode(), liquidation.positionSide(),
                        liquidation.triggerPriceSequence(),
                        liquidation.signedQuantitySteps(), liquidation.closeQuantitySteps(),
                        liquidation.deficitUnits(), liquidation.executionPriceTicks(),
                        liquidation.liquidationFeeRatePpm(), liquidation.liquidationFeeUnits(),
                        liquidation.status(), liquidation.nextCancelOrderId())));

        // 按品种编号排序的标记、指数及远期价格投影。
        Map<Integer, TradingRuntimeSnapshot.MarkPriceSnapshot> markPrices = new TreeMap<>();
        state.markPricesForSnapshot().forEachKeyValue((/* 当前行情、风控或生命周期进度对应的品种编号。 */ symbolId, /* 当前品种的价格与序列、生成时间。 */ mark) -> markPrices.put(symbolId,
                new TradingRuntimeSnapshot.MarkPriceSnapshot(mark.markPriceTicks(),
                        mark.indexPriceTicks(), mark.forwardPriceTicks(), mark.priceSequence(),
                        mark.generatedAtEpochMillis())));
        // 按用户与持仓键排序的风险计算结果投影。
        Map<TradingRuntimeSnapshot.PositionKey, TradingRuntimeSnapshot.RiskSnapshot> riskSnapshots = new TreeMap<>();
        state.riskSnapshotsForSnapshot().forEachKeyValue((/* 运行时已有的持仓索引键，与用户号组合成测试键。 */ positionKey, /* 当前持仓已保存的权益、未实现盈亏、维持保证金和风险状态。 */ risk) -> riskSnapshots.put(
                new TradingRuntimeSnapshot.PositionKey(risk.userId(), positionKey),
                new TradingRuntimeSnapshot.RiskSnapshot(risk.userId(), risk.symbolId(), risk.positionSide(),
                        risk.priceSequence(), risk.equityUnits(), risk.unrealizedPnlUnits(),
                        risk.maintenanceMarginUnits(), risk.marginRatioPpm(), risk.status())));
        // 按品种编号排序的风险及触发单续扫进度投影。
        Map<Integer, TradingRuntimeSnapshot.RiskScanSnapshot> riskScans = new TreeMap<>();
        state.riskScansForSnapshot().forEachKeyValue((/* 当前行情、风控或生命周期进度对应的品种编号。 */ symbolId, /* 当前品种的多阶段风险/触发游标，保留已有字段，不执行扫描。 */ scan) -> riskScans.put(symbolId,
                new TradingRuntimeSnapshot.RiskScanSnapshot(scan.accountLaneId(), scan.priceSequence(),
                        scan.scanStartPriceSequence(),
                        scan.lastUserId(), scan.riskComplete(), scan.riskUserId(), scan.riskPhase(),
                        scan.riskPositionCursor(), scan.riskReservationCursor(), scan.riskUnrealizedPnlUnits(),
                        scan.riskMaintenanceMarginUnits(), scan.riskIsolatedMarginUnits(),
                        scan.riskIsolatedReservationUnits(), scan.triggerComplete(), scan.triggerPhase(),
                        scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                        scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                        scan.triggerOcoCursor(), scan.lastScheduledRevision(), scan.laneProgress())));

        // 按资产编号排序的七类系统子账金额投影。
        Map<Integer, TradingRuntimeSnapshot.TreasurySnapshot> treasury = new TreeMap<>();
        // 从七类系统子账键收集并排序去重的资产集合。
        TreeSet<Integer> treasuryAssets = new TreeSet<>();
        state.treasury().feeBalances().forEachKeyValue((/* 当前余额或财库子账的整数资产编号。 */ assetId, /* 当前子账条目金额，收集资产键时不读取此值。 */ units) -> treasuryAssets.add(assetId));
        state.treasury().insuranceBalances().forEachKeyValue((/* 当前余额或财库子账的整数资产编号。 */ assetId, /* 当前子账条目金额，收集资产键时不读取此值。 */ units) -> treasuryAssets.add(assetId));
        state.treasury().insuranceDeficits().forEachKeyValue((/* 当前余额或财库子账的整数资产编号。 */ assetId, /* 当前子账条目金额，收集资产键时不读取此值。 */ units) -> treasuryAssets.add(assetId));
        state.treasury().liquidationFeeBalances().forEachKeyValue((/* 当前余额或财库子账的整数资产编号。 */ assetId, /* 当前子账条目金额，收集资产键时不读取此值。 */ units) -> treasuryAssets.add(assetId));
        state.treasury().fundingResidualBalances().forEachKeyValue((/* 当前余额或财库子账的整数资产编号。 */ assetId, /* 当前子账条目金额，收集资产键时不读取此值。 */ units) -> treasuryAssets.add(assetId));
        state.treasury().roundingResidualBalances().forEachKeyValue((/* 当前余额或财库子账的整数资产编号。 */ assetId, /* 当前子账条目金额，收集资产键时不读取此值。 */ units) -> treasuryAssets.add(assetId));
        state.treasury().clearingPnlBalances().forEachKeyValue((/* 当前余额或财库子账的整数资产编号。 */ assetId, /* 当前子账条目金额，收集资产键时不读取此值。 */ units) -> treasuryAssets.add(assetId));
        treasuryAssets.forEach(/* 当前余额或财库子账的整数资产编号。 */ assetId -> treasury.put(assetId, new TradingRuntimeSnapshot.TreasurySnapshot(
                state.treasury().fee(assetId), state.treasury().insurance(assetId),
                state.treasury().insuranceDeficit(assetId), state.treasury().liquidationFee(assetId),
                state.treasury().fundingResidual(assetId), state.treasury().roundingResidual(assetId),
                state.treasury().clearingPnl(assetId))));
        // 按品种保存的已完成资金费批次标记。
        Map<Integer, Long> fundingSettlements = new TreeMap<>();
        state.treasury().fundingSettlements().forEachKeyValue(fundingSettlements::put);
        // 按品种保存的资金费分批执行进度投影。
        Map<Integer, TradingRuntimeSnapshot.FundingProgressSnapshot> fundingProgress = new TreeMap<>();
        state.treasury().fundingProgresses().forEachKeyValue((/* 当前行情、风控或生命周期进度对应的品种编号。 */ symbolId, /* 当前资金费或到期结算的运行进度，按对应集合逐字段投影。 */ progress) -> fundingProgress.put(symbolId,
                new TradingRuntimeSnapshot.FundingProgressSnapshot(progress.settlementId(), progress.fundingRatePpm(),
                        progress.nextCursorUserId(), progress.markPriceTicks(), progress.priceSequence())));
        // 按品种保存的已完成到期/行权批次标记。
        Map<Integer, Long> lifecycleSettlements = new TreeMap<>();
        state.treasury().lifecycleSettlements().forEachKeyValue(lifecycleSettlements::put);
        // 按品种保存的到期结算分批执行进度投影。
        Map<Integer, TradingRuntimeSnapshot.LifecycleProgressSnapshot> lifecycleProgress = new TreeMap<>();
        state.treasury().lifecycleProgresses().forEachKeyValue((/* 当前行情、风控或生命周期进度对应的品种编号。 */ symbolId, /* 当前资金费或到期结算的运行进度，按对应集合逐字段投影。 */ progress) -> lifecycleProgress.put(symbolId,
                new TradingRuntimeSnapshot.LifecycleProgressSnapshot(progress.settlementId(), progress.settlementPriceTicks(),
                        progress.ordersComplete(), progress.nextCursorOrderId(), progress.nextCursorUserId(),
                        progress.requiredInsuranceUnits())));
        return new TradingRuntimeSnapshot(revision, users, balances, orders, reservations, clientOrderIndex,
                positions, liquidations, markPrices, riskSnapshots, riskScans, state.nextLiquidationId(), state.marketRevision(),
                new TreeMap<>(state.instrumentsForRuntime()), new TreeMap<>(state.leveragesForRuntime()),
                new TreeMap<>(state.algoOrdersForRuntime()), new TreeMap<>(state.cancelAllAfterTimersForRuntime()),
                new TreeMap<>(state.triggerOrdersForRuntime()), treasury, fundingSettlements, fundingProgress,
                lifecycleSettlements, lifecycleProgress);
    }
}
