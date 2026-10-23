package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreMarkPriceState;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.CoreRiskSnapshot;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.risk.*;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 在快照、查询和审计边界把运行时实体转回可导出的交易值。先各遍历一次预留和持仓并按用户分组，再构造用户、订单、风险与财库；尚未提交的预约从导出中排除，并还原对应余额和修订号。它不在撮合热路径维护另一份权威账户状态。 构造约定：工具类不创建实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeStateMaterializer {

    /**
     * 在 Owner 上取得快照视图，过滤待完成预约，将其冻结金额移回可用余额并减去预约贡献的修订次数；随后转换用户、订单、风险、客户别名等。财库通过原账本查询边界取得，预留或持仓引用了不存在的用户时拒绝导出。调用方须保证快照所需的线程和一致性边界。
     * @param runtime 由调用方安排好快照边界的原权威运行时，入口要求 Owner 线程
     * @param identities 把内部资产、币对、持仓和客户别名编号解析为业务名称的原身份表
     */
    public static TradingCoreState materialize(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
        if (runtime == null || identities == null) throw new IllegalArgumentException("runtime and identities required");
        runtime.assertOwner();
        // 当前快照边界可读的运行时用户视图。
        var runtimeUsers = runtime.usersForSnapshot();
        // 当前快照边界可读的按用户余额视图。
        var runtimeBalances = runtime.balancesForSnapshot();

        // 本次导出临时按用户归组的已提交资金预留，避免构造每个用户时重扫全表。
        Map<Long, Map<Long, OrderReservation>> reservationsByUser = new HashMap<>();

        runtime.reservationsForSnapshot().forEachKeyValue((/* 当前预留、订单或客户别名所对应的订单编号。 */ orderId, /* 当前运行时预留值，未完成预约随后从导出中跳过。 */ reservation) -> {

            if (runtime.pendingReservations().pendingReservation(orderId, reservation.userId())) return;
            if (!runtimeUsers.containsKey(reservation.userId())) {
                throw new IllegalStateException("reservation owner is not registered: " + reservation.userId());
            }
            reservationsByUser.computeIfAbsent(reservation.userId(), /* 首次为某用户创建分组时传入的用户编号，当前回调只创建空 Map。 */ ignored -> new HashMap<>())
                    .put(orderId, new OrderReservation(orderId, identities.instrumentId(reservation.symbolId()),
                            reservation.kind(), identities.asset(reservation.assetId()),
                            reservation.totalReservedUnits(), reservation.releasedUnits(), reservation.consumedUnits(),
                            reservation.orderQuantitySteps()));
        });
        // 本次导出临时按用户归组的持仓业务值。
        Map<Long, Map<String, CorePositionState>> positionsByUser = new HashMap<>();

        runtime.positionsForSnapshot().forEachKeyValue((/* 运行时持仓或风险快照的内部键。 */ positionKey, /* 当前要复制为业务值的持仓。 */ position) -> {

            if (!runtimeUsers.containsKey(position.userId())) {
                throw new IllegalStateException("position owner is not registered: " + position.userId());
            }
            // 身份表解析出的用户持仓业务键，用于用户持仓 Map。
            String key = identities.positionKey(position.userId(), positionKey);
            positionsByUser.computeIfAbsent(position.userId(), /* 首次为某用户创建分组时传入的用户编号，当前回调只创建空 Map。 */ ignored -> new HashMap<>())
                    .put(key, new CorePositionState(identities.instrumentId(position.symbolId()),
                            identities.asset(position.assetId()), position.marginMode(), position.positionSide(),
                            position.signedQuantitySteps(), position.entryPriceTicks(),
                            position.entryValueTicks(), position.realizedPnlUnits(), position.positionMarginUnits()));
        });

        // 本次导出的用户值，按用户编号排序。
        Map<Long, CoreUserState> users = new TreeMap<>();
        runtimeUsers.forEachKeyValue((/* 当前被导出用户的编号。 */ userId, /* 该用户原运行时修订号、产品线及账户模式。 */ user) -> {
            // 该用户的运行时余额表，可能不存在或为空。
            var userBalances = runtimeBalances.get(userId);
            // 该用户导出的资产余额值表，包含待完成预约金额的提交前还原。
            Map<String, AssetBalance> balances;
            if (userBalances == null || userBalances.isEmpty()) {
                balances = Map.of();
            } else {
                balances = new HashMap<>(userBalances.size());
                userBalances.forEachKeyValue((/* 当前用户余额的内部资产编号。 */ assetId, /* 运行时可用和冻结余额。 */ balance) -> {
                    // 内部资产编号对应的业务资产名称。
                    String asset = identities.asset(assetId);
                    // 本资产尚未提交的预约冻结额，导出时从冻结移回可用。
                    long pending = runtime.pendingReservations().pendingReservedUnits(userId, assetId);
                    balances.put(asset, new AssetBalance(asset, Math.addExact(balance.availableUnits(), pending),
                            Math.subtractExact(balance.lockedUnits(), pending)));
                });
            }
            // 预留全表预分组中该用户的条目，缺失时使用空表。
            Map<Long, OrderReservation> reservationValues = reservationsByUser.get(userId);
            // 写入该用户导出值的已提交资金预留表，仍可包含活动订单尚未释放的额度。
            Map<Long, OrderReservation> reservations = reservationValues == null ? Map.of() : reservationValues;
            // 持仓全表预分组中该用户的条目，缺失时使用空表。
            Map<String, CorePositionState> positionValues = positionsByUser.get(userId);
            // 写入该用户导出值的持仓表。
            Map<String, CorePositionState> positions = positionValues == null ? Map.of() : positionValues;
            users.put(userId, new CoreUserState(user.productLine(), userId,
                    Math.subtractExact(user.revision(), runtime.pendingReservations().pendingReservationCount(userId)), balances,
                    reservations, positions, user.positionMode()));
        });

        // 排除未提交预约后导出的订单表，按订单编号排序。
        Map<Long, CoreOrderState> orders = new TreeMap<>();
        runtime.ordersForSnapshot().forEachKeyValue((/* 当前预留、订单或客户别名所对应的订单编号。 */ orderId, /* 当前要复制数量、费用和状态等字段的运行时订单。 */ order) -> {
            if (runtime.pendingReservations().pendingReservation(orderId, order.userId())) return;
            orders.put(orderId,
                new CoreOrderState(orderId, order.productLine(), order.userId(), identities.instrumentId(order.symbolId()),
                        order.side(), order.priceTicks(), order.matchingPriceTicks(),
                        order.quantitySteps(),
                        order.executedQuantitySteps(), order.reduceOnly(),
                        order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                        order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                        order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.executedValueHigh(), order.executedValueLow(), order.createdAtEpochMillis(),
                        order.updatedAtEpochMillis(),
                        order.clusterPosition(), order.status(), order.revision()));
        });

        // 按币对名排序的标记价和参考价导出表。
        Map<String, CoreMarkPriceState> marks = new TreeMap<>();
        runtime.markPricesForSnapshot().forEachKeyValue((/* 当前标记价或扫描记录的内部币对编号。 */ symbolId, /* 当前币对的运行时价格记录。 */ mark) -> {
            // 从内部编号解析得到的币对业务名称。
            String instrumentId = identities.instrumentId(symbolId);
            marks.put(instrumentId, new CoreMarkPriceState(instrumentId, mark.markPriceTicks(),
                    mark.indexPriceTicks(), mark.forwardPriceTicks(), mark.priceSequence(),
                    mark.generatedAtEpochMillis(), mark.lastPriceTicks()));
        });
        // 按业务键排列的账户风险快照导出表。
        Map<String, CoreRiskSnapshot> riskSnapshots = new TreeMap<>();
        runtime.riskSnapshotsForSnapshot().forEachKeyValue((/* 运行时持仓或风险快照的内部键。 */ positionKey, /* 当前正在转换的单条账户风险快照。 */ risk) -> {
            // 由运行时风险条目构造的只读业务风险值。
            CoreRiskSnapshot snapshot = new CoreRiskSnapshot(risk.userId(), identities.instrumentId(risk.symbolId()),
                    risk.positionSide(), risk.priceSequence(), risk.equityUnits(), risk.unrealizedPnlUnits(),
                    risk.maintenanceMarginUnits(), risk.marginRatioPpm());
            riskSnapshots.put(snapshot.key(), snapshot);
        });
        // 按强平编号排序的强平业务记录导出表。
        Map<Long, CoreLiquidationState> liquidations = new TreeMap<>();
        runtime.liquidationsForSnapshot().forEachKeyValue((/* 当前强平业务记录的编号。 */ id, /* 当前强平记录的运行时值。 */ value) -> liquidations.put(id,
                new CoreLiquidationState(id, value.userId(), identities.instrumentId(value.symbolId()), value.marginMode(),
                        value.positionSide(), value.triggerPriceSequence(),
                        value.signedQuantitySteps(), value.closeQuantitySteps(), value.deficitUnits(),
                        value.executionPriceTicks(), value.liquidationFeeRatePpm(), value.liquidationFeeUnits(),
                        value.status(), value.nextCancelOrderId())));
        // 按币对名排序的风险与触发扫描进度导出表。
        Map<String, CoreRiskState.RiskScan> scans = new TreeMap<>();
        runtime.riskScansForSnapshot().forEachKeyValue((/* 当前标记价或扫描记录的内部币对编号。 */ symbolId, /* 当前币对尚未完成扫描的阶段与中间值。 */ scan) -> {
            // 从内部编号解析得到的币对业务名称。
            String instrumentId = identities.instrumentId(symbolId);
            scans.put(instrumentId, new CoreRiskState.RiskScan(instrumentId, scan.accountLaneId(), scan.priceSequence(),
                    scan.scanStartPriceSequence(), scan.lastUserId(), scan.riskComplete(), scan.riskUserId(),
                    scan.riskPhase(), scan.riskPositionCursor(), scan.riskReservationCursor(),
                    scan.riskUnrealizedPnlUnits(), scan.riskMaintenanceMarginUnits(),
                    scan.riskIsolatedMarginUnits(), scan.riskIsolatedReservationUnits(), scan.triggerComplete(),
                    scan.triggerPhase(), scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                    scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                    scan.triggerOcoCursor(), scan.lastScheduledRevision(), scan.laneProgress()));
        });
        // 汇总价格、风险快照、强平和扫描控制元数据的业务风险状态。
        CoreRiskState risk = new CoreRiskState(marks, riskSnapshots, liquidations, scans,
                runtime.nextLiquidationId(), runtime.riskScanControl(), runtime.marketRevision());

        // 通过原账本所有者查询边界导出的财库状态。
        CoreTreasuryState treasury = runtime.treasuryForQuery(identities);

        // 排除待完成预约后，按用户与客户订单号排序的业务别名映射。
        Map<TradingCoreState.ClientOrderKey, Long> clientIndex = new TreeMap<>();
        runtime.clientOrderIndexForSnapshot().forEachKeyValue((/* 当前被导出用户的编号。 */ userId, /* 当前用户的内部客户别名到订单编号的运行时映射。 */ entries) ->
                entries.forEachKeyValue((/* 当前客户订单号的用户内身份编号。 */ clientKey, /* 当前预留、订单或客户别名所对应的订单编号。 */ orderId) -> {
                    if (!runtime.pendingReservations().pendingReservation(orderId, userId)) {
                        clientIndex.put(new TradingCoreState.ClientOrderKey(
                                userId, identities.clientOrderId(userId, clientKey)), orderId);
                    }
                }));
        return new TradingCoreState(runtime.productLine(),
                Math.subtractExact(runtime.revision(), runtime.pendingReservations().pendingReservationCount()), users, orders,
                runtime.instrumentsForRuntime(), risk, treasury,
                new TreeMap<>(runtime.leveragesForRuntime()), new TreeMap<>(runtime.algoOrdersForRuntime()),
                new TreeMap<>(runtime.cancelAllAfterTimersForRuntime()), clientIndex,
                new TreeMap<>(runtime.triggerOrdersForRuntime()));
    }

    /**
     * 由财库唯一所有者线程遍历各子账和进度，把内部资产、币对编号解析为业务名称，构造 CoreTreasuryState。这里执行全表导出，不在账户热路径复制财库。
     * @param ledger 当前所有者线程可以遍历的原 TreasuryRuntime
     * @param identities 把内部资产、币对、持仓和客户别名编号解析为业务名称的原身份表
     */
    public static CoreTreasuryState materializeTreasury(TreasuryRuntime ledger, RuntimeIdentityRegistry identities) {
        // 由财库手续费子账导出的资产金额表。
        Map<String, Long> fees = new TreeMap<>();
        ledger.feeBalances().forEachKeyValue((/* 手续费子账条目的内部资产编号。 */ id, /* 该资产在对应财库子账中的有符号金额。 */ units) -> fees.put(identities.asset(id), units));
        // 由财库保险余额导出的资产金额表。
        Map<String, Long> insurance = new TreeMap<>();
        ledger.insuranceBalances().forEachKeyValue((/* 保险余额条目的内部资产编号。 */ id, /* 该资产在对应财库子账中的有符号金额。 */ units) -> insurance.put(identities.asset(id), units));
        // 由财库保险亏空导出的资产金额表。
        Map<String, Long> deficits = new TreeMap<>();
        ledger.insuranceDeficits().forEachKeyValue((/* 保险亏空条目的内部资产编号。 */ id, /* 该资产在对应财库子账中的有符号金额。 */ units) -> deficits.put(identities.asset(id), units));
        // 由财库强平费用子账导出的资产金额表。
        Map<String, Long> liquidationFees = new TreeMap<>();
        ledger.liquidationFeeBalances().forEachKeyValue((/* 强平费用条目的内部资产编号。 */ id, /* 该资产在对应财库子账中的有符号金额。 */ units) ->
                liquidationFees.put(identities.asset(id), units));
        // 由财库资金费剩余差额导出的资产金额表。
        Map<String, Long> fundingResiduals = new TreeMap<>();
        ledger.fundingResidualBalances().forEachKeyValue((/* 资金费剩余条目的内部资产编号。 */ id, /* 该资产在对应财库子账中的有符号金额。 */ units) ->
                fundingResiduals.put(identities.asset(id), units));
        // 由财库舍入剩余差额导出的资产金额表。
        Map<String, Long> roundingResiduals = new TreeMap<>();
        ledger.roundingResidualBalances().forEachKeyValue((/* 舍入剩余条目的内部资产编号。 */ id, /* 该资产在对应财库子账中的有符号金额。 */ units) ->
                roundingResiduals.put(identities.asset(id), units));
        // 由财库清算盈亏子账导出的资产金额表。
        Map<String, Long> clearingPnl = new TreeMap<>();
        ledger.clearingPnlBalances().forEachKeyValue((/* 清算盈亏条目的内部资产编号。 */ id, /* 该资产在对应财库子账中的有符号金额。 */ units) ->
                clearingPnl.put(identities.asset(id), units));
        // 币对名称到已完成资金费编号的导出表。
        Map<String, Long> funding = new TreeMap<>();
        ledger.fundingSettlements().forEachKeyValue((/* 已完成资金费记录的内部币对编号。 */ id, /* 币对已完整完成的资金费结算编号。 */ value) -> funding.put(identities.instrumentId(id), value));
        // 币对名称到已完成生命周期结算编号的导出表。
        Map<String, Long> lifecycle = new TreeMap<>();
        ledger.lifecycleSettlements().forEachKeyValue((/* 已完成生命周期结算记录的内部币对编号。 */ id, /* 币对已完整完成的生命周期结算编号。 */ value) -> lifecycle.put(identities.instrumentId(id), value));
        // 尚未完成资金费的币对名称到进度导出表。
        Map<String, CoreTreasuryState.FundingProgress> fundingProgress = new TreeMap<>();
        ledger.fundingProgresses().forEachKeyValue((/* 资金费续页记录的内部币对编号。 */ id, /* 包含固定价格和用户游标的运行时资金费进度。 */ value) -> fundingProgress.put(
                identities.instrumentId(id), new CoreTreasuryState.FundingProgress(value.settlementId(),
                        value.fundingRatePpm(), value.nextCursorUserId(), value.markPriceTicks(), value.priceSequence())));
        // 尚未完成生命周期结算的币对名称到进度导出表。
        Map<String, CoreTreasuryState.LifecycleProgress> lifecycleProgress = new TreeMap<>();
        ledger.lifecycleProgresses().forEachKeyValue((/* 生命周期结算续页记录的内部币对编号。 */ id, /* 包含双游标与保险需求的运行时结算进度。 */ value) -> lifecycleProgress.put(
                identities.instrumentId(id), new CoreTreasuryState.LifecycleProgress(value.settlementId(),
                        value.settlementPriceTicks(), value.ordersComplete(), value.nextCursorOrderId(),
                        value.nextCursorUserId(), value.requiredInsuranceUnits())));
        return new CoreTreasuryState(fees, insurance, deficits, liquidationFees,
                fundingResiduals, roundingResiduals, clearingPnl, funding, lifecycle,
                fundingProgress, lifecycleProgress);
    }


}
