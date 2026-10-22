package com.surprising.aeron.service.account;

import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.change.RuntimeUserChangeBuffer;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.risk.RiskSnapshotRuntime;
import com.surprising.aeron.service.settlement.MatcherSettlementChanges;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

/**
 * 按账户 Lane 保存当前命令首次修改前的用户、余额、订单、预留、持仓及客户映射等前值。回滚先由每个 Lane 恢复权威数据，再由 Owner 恢复发布引用；余额后值另行发布和清空。Matcher 结算已有独立变化作用域时多数前值入口跳过重复捕获，余额则进入对应结算补丁。
 */
public final class RuntimeAccountRollback {
    /** 拥有产品元数据及账户 Lane 路由的交易运行时。 */
    private final TradingAccountRuntime state;
    /** 按 Lane 保存用户首次修改前值及必要的可复用用户槽。 */
    final RuntimeUserChangeBuffer[] patchUsersBeforeByLane;
    /** 按 Lane 保存用户资产余额前值和最终后值，发布后单独清空。 */
    final LaneBalancePatches[] patchBalancesBeforeByLane;
    /** 按 Lane 保存订单预留的原值与原待完成标记。 */
    final LaneLongCaptures<PatchReservationBefore>[] patchReservationsBeforeByLane;
    /** 按 Lane 保存订单原值、待完成标记和恢复用权威引用。 */
    final LaneLongCaptures<PatchOrderBefore>[] patchOrdersBeforeByLane;
    /** 按 Lane 保存持仓首次修改前值，可用 null 表示原来不存在。 */
    final LaneLongCaptures<PositionRuntime>[] patchPositionsBeforeByLane;
    /** 按 Lane 保存用户客户身份键原先指向的订单编号。 */
    final LaneClientOrderCaptures[] patchClientOrdersBeforeByLane;
    /** 按 Lane 保存触发记录首次修改前值。 */
    final LaneLongCaptures<CoreTriggerOrderState>[] patchTriggersBeforeByLane;
    /** 按 Lane 保存风险估值首次修改前值。 */
    final LaneLongCaptures<RiskSnapshotRuntime>[] patchRiskSnapshotsBeforeByLane;

    /**
     * 按实际 Lane 数量创建各类可复用捕获缓冲，不复制整份账户状态，也不启动工作线程。
     * @param state 处理本产品线及账户 Lane 的交易运行时
     * @param laneCount 按当前拓扑分配的账户 Lane 数量
     */
    public @SuppressWarnings("unchecked")
    RuntimeAccountRollback(TradingAccountRuntime state, int laneCount) {
        this.state = java.util.Objects.requireNonNull(state);
        patchUsersBeforeByLane = new RuntimeUserChangeBuffer[laneCount];
        patchBalancesBeforeByLane = new LaneBalancePatches[laneCount];
        patchReservationsBeforeByLane = (LaneLongCaptures<PatchReservationBefore>[]) new LaneLongCaptures<?>[laneCount];
        patchOrdersBeforeByLane = (LaneLongCaptures<PatchOrderBefore>[]) new LaneLongCaptures<?>[laneCount];
        patchPositionsBeforeByLane = (LaneLongCaptures<PositionRuntime>[]) new LaneLongCaptures<?>[laneCount];
        patchClientOrdersBeforeByLane = new LaneClientOrderCaptures[laneCount];
        patchTriggersBeforeByLane = (LaneLongCaptures<CoreTriggerOrderState>[]) new LaneLongCaptures<?>[laneCount];
        patchRiskSnapshotsBeforeByLane = (LaneLongCaptures<RiskSnapshotRuntime>[]) new LaneLongCaptures<?>[laneCount];
        for (/* 当前需要捕获、恢复或清理的账户 Lane 数组下标。 */ int laneId = 0; laneId < laneCount; laneId++) {
            patchUsersBeforeByLane[laneId] = new RuntimeUserChangeBuffer();
            patchBalancesBeforeByLane[laneId] = new LaneBalancePatches();
            patchReservationsBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchOrdersBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchPositionsBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchClientOrdersBeforeByLane[laneId] = new LaneClientOrderCaptures();
            patchTriggersBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchRiskSnapshotsBeforeByLane[laneId] = new LaneLongCaptures<>();
        }
    }

    /** 原订单不存在且没有待完成准入的共享捕获记录，避免为每个新增订单创建哨兵对象。 */
    private static final PatchOrderBefore ABSENT_ORDER_BEFORE = new PatchOrderBefore(null, false);
    /** 原预留不存在且不待完成的共享捕获记录。 */
    private static final PatchReservationBefore ABSENT_RESERVATION_BEFORE = new PatchReservationBefore(null, false);

    /**
     * 非 Matcher 结算作用域内，按用户路由记录首次修改前的用户值；同命令重复修改不覆盖最早前值。
     * @param userId 本次捕获实体所属的用户编号
     */
    public void captureUserBefore(long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        // 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。
        RuntimeUserChangeBuffer captured =
                patchUsersBeforeByLane[state.topology().accountLaneId(userId)];
        if (!captured.containsKey(userId)) captured.captureBefore(userId, state.user(userId));
    }

    /**
     * 在批量准入有序提交时接收 Lane 已捕获的用户前值，并为本批新订单及预留保存不存在标记，避免逐项全局查找和跨 Lane 扫描。
     * @param userId 本次捕获实体所属的用户编号
     * @param userBefore Lane 准入开始前已捕获的用户值，可用 null 表示用户原来不存在
     * @param admittedOrders Lane 本批已经新建的订单数组，只读取有效前缀
     * @param itemCount 本批有效新订单数量
     * @param laneId 本批新订单及用户所属的实际 Lane 编号
     */
    public void captureBatchAdmissionBefore(long userId, UserRuntime userBefore, OrderRuntime[] admittedOrders,
                                             int itemCount, int laneId) {
        if (userId <= 0 || admittedOrders == null || itemCount <= 0 || itemCount > admittedOrders.length
                || laneId < 0 || laneId >= patchOrdersBeforeByLane.length) {
            throw new IllegalArgumentException("invalid batch admission before-image");
        }
        // 本批目标 Lane 的用户前值缓冲。
        RuntimeUserChangeBuffer userCaptures = patchUsersBeforeByLane[laneId];
        if (!userCaptures.containsKey(userId)) userCaptures.put(userId, userBefore);
        // 本批目标 Lane 的订单前值缓冲。
        LaneLongCaptures<PatchOrderBefore> orderCaptures = patchOrdersBeforeByLane[laneId];
        // 本批目标 Lane 的预留前值缓冲。
        LaneLongCaptures<PatchReservationBefore> reservationCaptures = patchReservationsBeforeByLane[laneId];
        for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < itemCount; index++) {
            // 当前准入数组中的新订单，用户必须与本批相同。
            OrderRuntime admitted = admittedOrders[index];
            if (admitted == null || admitted.userId() != userId) {
                throw new IllegalStateException("batch admission order owner mismatch");
            }
            // 当前新建或恢复的预留所对应的订单编号。
            long orderId = admitted.orderId();
            if (!orderCaptures.containsKey(orderId)) orderCaptures.put(orderId, ABSENT_ORDER_BEFORE);
            if (!reservationCaptures.containsKey(orderId)) {
                reservationCaptures.put(orderId, ABSENT_RESERVATION_BEFORE);
            }
        }
    }

    /**
     * 由该 Lane 所属线程依次恢复客户映射、订单及预留、持仓、余额、用户、触发和风险记录及本地成员索引；回滚后的余额同时捕获为最终后值，不在此恢复 Owner 发布索引。
     * @param lane 当前实际拥有用户数据的账户 Lane
     */
    public void restoreLane(AccountLaneState lane) {
        lane.assertOwner();
        // 当前需要捕获、恢复或清理的账户 Lane 数组下标。
        int laneId = lane.laneId();
        // 本 Lane 的用户客户号映射前值列表。
        LaneClientOrderCaptures clients = patchClientOrdersBeforeByLane[laneId];
        for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < clients.size(); index++) {
            // 当前待恢复余额、用户或客户映射所属的用户编号。
            long userId = clients.userId(index);
            // 需要恢复订单指向的客户身份整数键。
            long clientKey = clients.clientKey(index);
            TradingAccountRuntime.removeClientOrderIndex(lane, userId, clientKey);
            // 当前实体保存的命令前值，null 表示该实体原来不存在。
            Long before = clients.beforeOrderId(index);
            if (before != null) TradingAccountRuntime.putClientOrderIndex(lane, userId, clientKey, before);
        }
        // 当前 Lane 的订单前值缓冲，恢复时先移除现有记录再重装原权威对象。
        LaneLongCaptures<PatchOrderBefore> orders = patchOrdersBeforeByLane[laneId];
        for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < orders.size(); index++) {
            lane.removeOrder(orders.key(index));
            // 该订单的前值及原权威对象捕获记录。
            PatchOrderBefore captured = orders.value(index);
            // 当前实体保存的命令前值，null 表示该实体原来不存在。
            OrderRuntime before = captured.value();
            if (before != null) {
                // 应保留或复用的 Lane 权威对象，先恢复前值内容再重新登记。
                OrderRuntime authority = captured.authority();
                if (authority != before) authority.restoreCapturedValue(before);
                lane.putOrder(authority);
            }
        }
        // 当前 Lane 的预留前值缓冲，拒绝回滚重叠的已有待完成预留。
        LaneLongCaptures<PatchReservationBefore> reservations = patchReservationsBeforeByLane[laneId];
        for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < reservations.size(); index++) {
            // 当前新建或恢复的预留所对应的订单编号。
            long orderId = reservations.key(index);
            // 该预留的前值及原待完成标记。
            PatchReservationBefore captured = reservations.value(index);
            if (captured.pending())
                throw new IllegalStateException("order batch overlapped an existing pending reservation");
            // 刚从权威表移除的当前预留或持仓，先用它清理现有成员索引。
            ReservationRuntime current = lane.reservations.remove(orderId);
            if (current != null) {
                lane.removeReservationId(current.userId(), orderId);
            }
            // 当前实体保存的命令前值，null 表示该实体原来不存在。
            ReservationRuntime before = captured.value();
            if (before != null) {
                lane.reservations.put(orderId, before.laneValue());
                lane.addReservationId(before.userId(), orderId);
            }
        }
        // 当前 Lane 的持仓前值缓冲，恢复时同步用户和合约持仓索引。
        LaneLongCaptures<PositionRuntime> positions = patchPositionsBeforeByLane[laneId];
        for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < positions.size(); index++) {
            // 当前要恢复持仓或风险记录的运行时键。
            long positionKey = positions.key(index);
            // 刚从权威表移除的当前预留或持仓，先用它清理现有成员索引。
            PositionRuntime current = lane.positions.remove(positionKey);
            if (current != null) TradingAccountRuntime.unindexPosition(lane, positionKey, current);
            // 当前实体保存的命令前值，null 表示该实体原来不存在。
            PositionRuntime before = positions.value(index);
            if (before != null) {
                lane.positions.put(positionKey, before.laneValue());
                TradingAccountRuntime.indexPosition(lane, positionKey, before);
            }
        }
        // 本 Lane 的余额补丁，恢复金额后还要捕获回滚最终后值。
        LaneBalancePatches balances = patchBalancesBeforeByLane[laneId];
        for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < balances.size(); index++) {
            // 当前待恢复余额、用户或客户映射所属的用户编号。
            long userId = balances.userId(index);
            // 当前恢复余额的资产整数编号。
            int assetId = balances.assetId(index);
            // 当前实体保存的命令前值，null 表示该实体原来不存在。
            BalanceState before = balances.before(index);
            // 该用户实际资产余额表，原余额不存在时可能需要删除空表，存在时可能需要新建表。
            IntObjectHashMap<BalanceRuntime> userBalances = lane.balances.get(userId);
            if (before == null) {
                if (userBalances != null) {
                    userBalances.remove(assetId);
                    if (userBalances.isEmpty()) lane.balances.remove(userId);
                }
            } else {
                if (userBalances == null) {
                    userBalances = new IntObjectHashMap<>();
                    lane.balances.put(userId, userBalances);
                }
                userBalances.put(assetId, new BalanceRuntime(userId, assetId,
                        before.availableUnits(), before.lockedUnits()));
            }
            // 即使失败发生在首次写入前，回滚结果也要作为最终后值捕获，不能发布尝试值或未捕获值。
            balances.after(userId, assetId, userBalances == null ? null : userBalances.get(assetId),
                    lane.pendingReservedUnits(userId, assetId));
        }
        // 当前 Lane 的用户前值缓冲，原用户不存在时清理其成员资格及相关容器。
        RuntimeUserChangeBuffer users = patchUsersBeforeByLane[laneId];
        for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < users.size(); index++) {
            // 当前待恢复余额、用户或客户映射所属的用户编号。
            long userId = users.keyAt(index);
            // 当前实体保存的命令前值，null 表示该实体原来不存在。
            UserRuntime before = users.valueAt(index);
            if (before == null) {
                lane.users.remove(userId);
                lane.removeUser(userId);
                lane.clientOrderIndex.remove(userId);
                lane.reservationIdsByUser.remove(userId);
                lane.activeOrderIdsByUser.remove(userId);
            } else {
                // 应保留或复用的 Lane 权威对象，先恢复前值内容再重新登记。
                UserRuntime authority = users.hasPreparedAt(index) ? users.preparedAt(index) : lane.users.get(userId);
                if (state.accountLanesStarted()) {
                    authority = (authority == null ? before : authority).laneValue()
                            .withState(before.revision(), before.positionMode());
                } else authority = before.publicationValue();
                lane.users.put(userId, authority);
                lane.registerUser(userId);
            }
        }
        // 当前 Lane 的触发记录前值缓冲，恢复时同步用户触发编号索引。
        LaneLongCaptures<CoreTriggerOrderState> triggers = patchTriggersBeforeByLane[laneId];
        for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < triggers.size(); index++) {
            // 当前要恢复的触发记录编号。
            long triggerId = triggers.key(index);
            // 当前实体保存的命令前值，null 表示该实体原来不存在。
            CoreTriggerOrderState before = triggers.value(index);
            lane.removeTrigger(triggerId);
            if (before != null) {
                lane.putTrigger(before);
            }
        }
        // 当前 Lane 的风险估值前值缓冲。
        LaneLongCaptures<RiskSnapshotRuntime> snapshots = patchRiskSnapshotsBeforeByLane[laneId];
        for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < snapshots.size(); index++) {
            // 当前要恢复持仓或风险记录的运行时键。
            long positionKey = snapshots.key(index);
            // 当前实体保存的命令前值，null 表示该实体原来不存在。
            RiskSnapshotRuntime before = snapshots.value(index);
            if (before == null) {
                lane.cold.riskSnapshots.remove(positionKey);
            } else {
                lane.cold.riskSnapshots.put(positionKey, before);
            }
        }
    }

    /**
     * 必须等全部 Lane 账户数据恢复后，由 Owner 逐项恢复订单、预留、持仓、用户、触发及风险的发布引用；不在此写账户权威表或直接发布余额。
     */
    public void restoreOwner() {
        state.assertOwner();
        for (LaneLongCaptures<PatchOrderBefore> /* 当前 Lane 的订单前值缓冲，恢复时先移除现有记录再重装原权威对象。 */ orders : patchOrdersBeforeByLane)
            for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < orders.size(); index++)
                state.publishOrder(orders.key(index), orders.value(index).value());
        for (LaneLongCaptures<PatchReservationBefore> /* 当前 Lane 的预留前值缓冲，拒绝回滚重叠的已有待完成预留。 */ reservations : patchReservationsBeforeByLane)
            for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < reservations.size(); index++)
                state.publishReservation(reservations.key(index), reservations.value(index).value());
        for (LaneLongCaptures<PositionRuntime> /* 当前 Lane 的持仓前值缓冲，恢复时同步用户和合约持仓索引。 */ positions : patchPositionsBeforeByLane)
            for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < positions.size(); index++)
                state.publishPosition(positions.key(index), positions.value(index));
        for (RuntimeUserChangeBuffer /* 当前 Lane 的用户前值缓冲，原用户不存在时清理其成员资格及相关容器。 */ users : patchUsersBeforeByLane)
            for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < users.size(); index++)
                state.publishUser(users.keyAt(index), users.valueAt(index));
        for (LaneLongCaptures<CoreTriggerOrderState> /* 当前 Lane 的触发记录前值缓冲，恢复时同步用户触发编号索引。 */ triggers : patchTriggersBeforeByLane)
            for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < triggers.size(); index++)
                state.publishTriggerOrder(triggers.key(index), triggers.value(index));
        for (LaneLongCaptures<RiskSnapshotRuntime> /* 当前 Lane 的风险估值前值缓冲。 */ snapshots : patchRiskSnapshotsBeforeByLane)
            for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < snapshots.size(); index++)
                state.publishRiskSnapshot(snapshots.key(index), snapshots.value(index));
    }

    /**
     * 按当前 Matcher 结算或普通回滚作用域选择余额补丁，已有前值则跳过；使用当前 Lane 作用域，或通过 onLane 到所属账户捕获。
     * @param userId 本次捕获实体所属的用户编号
     * @param assetId 需要捕获余额的运行时资产编号
     */
    public void captureBalanceBefore(long userId, int assetId) {
        // 当前 Matcher 结算变化作用域，存在时余额捕获归入其专用补丁。
        MatcherSettlementChanges changes = state.currentMatcherSettlementChangesScope();
        // 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。
        LaneBalancePatches captured = changes == null
                ? patchBalancesBeforeByLane[state.topology().accountLaneId(userId)]
                : changes.balancePatch(state.topology().accountLaneId(userId));
        if (captured.contains(userId, assetId)) return;
        // 已经存在的 Lane 命令作用域，优先在该作用域直接捕获。
        AccountLaneState scoped = state.currentLaneCommandScope();
        if (scoped != null) {
            captureBalanceBefore(scoped, captured, userId, assetId);
            return;
        }
        state.onLane(userId, /* onLane 回调实际提供的所属账户 Lane。 */ lane -> {
            captureBalanceBefore(lane, captured, userId, assetId);
            return null;
        });
    }

    /**
     * 读取指定 Lane 用户资产余额与待完成预留合计，追加到调用方选定的前值缓冲；本入口不检查是否已重复捕获。
     * @param lane 当前实际拥有用户数据的账户 Lane
     * @param captured 本次选定的余额前后值补丁，调用方应先排除重复前值
     * @param userId 本次捕获实体所属的用户编号
     * @param assetId 需要捕获余额的运行时资产编号
     */
    public static void captureBalanceBefore(AccountLaneState lane, LaneBalancePatches captured,
                                             long userId, int assetId) {
        // 当前用户资产编号到余额单元的权威表，资产尚未创建时可以为空。
        IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
        // 当前用户资产的权威余额值，缺失时捕获不存在。
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        captured.add(userId, assetId, balance, lane.pendingReservedUnits(userId, assetId));
    }

    /**
     * 显式持有 Lane 时，选择该 Lane 普通或 Matcher 结算补丁，只在该用户资产尚未捕获时保存前值。
     * @param lane 当前实际拥有用户数据的账户 Lane
     * @param userId 本次捕获实体所属的用户编号
     * @param assetId 需要捕获余额的运行时资产编号
     */
    public void captureBalanceBefore(AccountLaneState lane, long userId, int assetId) {
        // 当前 Matcher 结算变化作用域，存在时余额捕获归入其专用补丁。
        MatcherSettlementChanges changes = lane.matcherSettlementChanges;
        // 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。
        LaneBalancePatches captured = changes == null ? patchBalancesBeforeByLane[lane.laneId()]
                : changes.balancePatch(lane.laneId());
        if (!captured.contains(userId, assetId)) captureBalanceBefore(lane, captured, userId, assetId);
    }

    /**
     * 在所属 Lane 上把当前余额及待完成预留合计写入对应补丁后值，要求同一作用域已存在前值。
     * @param lane 当前实际拥有用户数据的账户 Lane
     * @param userId 本次捕获实体所属的用户编号
     * @param assetId 需要捕获余额的运行时资产编号
     */
    public void captureBalanceAfter(AccountLaneState lane, long userId, int assetId) {
        // 当前 Matcher 结算变化作用域，存在时余额捕获归入其专用补丁。
        MatcherSettlementChanges changes = lane.matcherSettlementChanges;
        // 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。
        LaneBalancePatches captured = changes == null
                ? patchBalancesBeforeByLane[lane.laneId()]
                : changes.balancePatch(lane.laneId());
        // 当前用户资产编号到余额单元的权威表，资产尚未创建时可以为空。
        IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
        // 当前用户资产的权威余额值，缺失时捕获不存在。
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        captured.after(userId, assetId, balance, lane.pendingReservedUnits(userId, assetId));
    }

    /**
     * 未提供用户路由时委托完整入口，从当前订单或命令作用域推断捕获 Lane。
     * @param orderId 需要捕获或查找前值的订单编号
     */
    public void captureOrderBefore(long orderId) {
        captureOrderBefore(orderId, 0);
    }

    /**
     * 普通命令作用域内保留订单最早前值及待完成标记；先排除已捕获项，订单原本不存在则存共享不存在记录。
     * @param orderId 需要捕获或查找前值的订单编号
     * @param userId 已知订单用户路由，零表示尝试从当前订单或执行作用域确定
     */
    public void captureOrderBefore(long orderId, long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        if (capturedOrderBefore(orderId) != null) return;
        // 当前查到的订单或预留原值或捕获包装，null 的含义由 containsKey 标记区分。
        OrderRuntime value = state.order(orderId);
        // 显式提供的用户优先，否则从当前订单或预留解析出的用户路由。
        long resolvedUserId = userId > 0 ? userId : (value == null ? 0 : value.userId());
        // 当前需要捕获、恢复或清理的账户 Lane 数组下标。
        int laneId = captureLane(orderId, resolvedUserId);
        // 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。
        LaneLongCaptures<PatchOrderBefore> captured = patchOrdersBeforeByLane[laneId];
        if (!captured.containsKey(orderId)) {
            captured.put(orderId, value == null ? ABSENT_ORDER_BEFORE
                    : new PatchOrderBefore(value, state.pendingReservations().pendingReservation(orderId, value.userId())));
        }
    }

    /**
     * 原位修改订单元数据前冻结命令前值，并保留原 Lane 权威对象用于恢复；若早先捕获仍引用同一活对象，则以身份校验替换成冻结记录。
     * @param order 准备原位修改元数据的 Lane 权威订单
     */
    public void captureOrderMetadataBefore(OrderRuntime order) {
        // 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。
        var captured = patchOrdersBeforeByLane[state.topology().accountLaneId(order.userId())];
        // 此前已捕获的订单包装记录；若仍直接引用活订单，元数据写入前须先冻结。
        var before = captured.get(order.orderId());
        if (!captured.containsKey(order.orderId()))
            captured.put(order.orderId(), new PatchOrderBefore(order.snapshot(),
                    state.pendingReservations().pendingReservation(order.orderId(), order.userId()), order));
        else if (before != null && before.value() == order)
            captured.replaceReference(order.orderId(), before, new PatchOrderBefore(order.snapshot(), before.pending(), before.authority()));
    }

    /**
     * 未提供用户路由时委托完整入口捕获订单预留前值。
     * @param orderId 需要捕获或查找前值的订单编号
     */
    public void captureReservationBefore(long orderId) {
        captureReservationBefore(orderId, 0);
    }

    /**
     * 普通命令作用域内保留首次预留前值及是否待完成标记，预留不存在时仍记录存在性哨兵。
     * @param orderId 需要捕获或查找前值的订单编号
     * @param userId 已知预留用户路由，零表示尝试从当前预留或执行作用域确定
     */
    public void captureReservationBefore(long orderId, long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        if (capturedReservationBefore(orderId) != null) return;
        // 当前查到的订单或预留原值或捕获包装，null 的含义由 containsKey 标记区分。
        ReservationRuntime value = state.reservation(orderId);
        // 显式提供的用户优先，否则从当前订单或预留解析出的用户路由。
        long resolvedUserId = userId > 0 ? userId : (value == null ? 0 : value.userId());
        // 当前需要捕获、恢复或清理的账户 Lane 数组下标。
        int laneId = captureLane(orderId, resolvedUserId);
        // 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。
        LaneLongCaptures<PatchReservationBefore> captured = patchReservationsBeforeByLane[laneId];
        if (!captured.containsKey(orderId)) {
            captured.put(orderId, value == null ? ABSENT_RESERVATION_BEFORE
                    : new PatchReservationBefore(value, state.pendingReservations().pendingReservation(orderId, value.userId())));
        }
    }

    /**
     * 优先沿用当前 Lane 作用域，其次按已知用户路由；两者都未知时用实体编号哈希选择保存不存在前值的缓冲分组。
     * @param entityId 没有已知用户时用于选择捕获分组的实体编号
     * @param userId 已知用户编号，非正数表示没有用户路由可用
     */
    int captureLane(long entityId, long userId) {
        // 已经存在的 Lane 命令作用域，优先在该作用域直接捕获。
        AccountLaneState scoped = state.currentLaneCommandScope();
        if (scoped != null) return scoped.laneId();
        if (userId > 0) return state.topology().accountLaneId(userId);
        return Math.floorMod(Long.hashCode(entityId), state.accountLaneCount());
    }

    /**
     * 顺序查找各 Lane 已捕获的订单记录，不把共享不存在哨兵与尚未捕获混为一谈。
     * @param orderId 需要捕获或查找前值的订单编号
     */
    PatchOrderBefore capturedOrderBefore(long orderId) {
        for (LaneLongCaptures<PatchOrderBefore> /* 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。 */ captured : patchOrdersBeforeByLane) {
            // 当前查到的订单或预留原值或捕获包装，null 的含义由 containsKey 标记区分。
            PatchOrderBefore value = captured.get(orderId);
            if (value != null || captured.containsKey(orderId)) return value;
        }
        return null;
    }

    /**
     * 顺序查找各 Lane 已捕获的预留前值记录，不返回当前活跃预留。
     * @param orderId 需要捕获或查找前值的订单编号
     */
    PatchReservationBefore capturedReservationBefore(long orderId) {
        for (LaneLongCaptures<PatchReservationBefore> /* 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。 */ captured : patchReservationsBeforeByLane) {
            // 当前查到的订单或预留原值或捕获包装，null 的含义由 containsKey 标记区分。
            PatchReservationBefore value = captured.get(orderId);
            if (value != null || captured.containsKey(orderId)) return value;
        }
        return null;
    }

    /**
     * 以零作为未知用户后备值，委托完整入口捕获持仓前值。
     * @param positionKey 需要保存前值的运行时持仓键
     */
    public void capturePositionBefore(long positionKey) {
        capturePositionBefore(positionKey, 0);
    }

    /**
     * 普通命令内读取原持仓，按其用户或调用方后备用户路由首次前值；两者都无法确定正数用户时不保存。
     * @param positionKey 需要保存前值的运行时持仓键
     * @param fallbackUserId 原持仓不存在时使用的用户编号，零表示无法提供用户路由
     */
    public void capturePositionBefore(long positionKey, long fallbackUserId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        // 当前实体保存的命令前值，null 表示该实体原来不存在。
        PositionRuntime before = state.position(positionKey);
        // 当前待恢复余额、用户或客户映射所属的用户编号。
        long userId = before == null ? fallbackUserId : before.userId();
        if (userId > 0) {
            // 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。
            LaneLongCaptures<PositionRuntime> captured =
                    patchPositionsBeforeByLane[state.topology().accountLaneId(userId)];
            if (!captured.containsKey(positionKey)) captured.put(positionKey, before);
        }
    }

    /**
     * 普通命令内按用户保存该客户身份键最早的订单指向，原映射不存在也保留存在标记。
     * @param userId 本次捕获实体所属的用户编号
     * @param clientKey 正数客户身份键，供当前用户映射前值捕获
     */
    public void captureClientOrderBefore(long userId, long clientKey) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        // 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。
        LaneClientOrderCaptures captured = patchClientOrdersBeforeByLane[state.topology().accountLaneId(userId)];
        if (!captured.contains(userId, clientKey)) {
            captured.add(userId, clientKey, state.orderIdByClient(userId, clientKey));
        }
    }

    /**
     * 普通命令内按用户保存触发单首次修改前的记录，null 表示原来不存在。
     * @param triggerOrderId 需要保存前值的触发单编号
     * @param userId 本次捕获实体所属的用户编号
     */
    public void captureTriggerOrderBefore(long triggerOrderId, long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        // 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。
        LaneLongCaptures<CoreTriggerOrderState> captured =
                patchTriggersBeforeByLane[state.topology().accountLaneId(userId)];
        if (!captured.containsKey(triggerOrderId)) {
            captured.put(triggerOrderId, state.triggerOrder(triggerOrderId));
        }
    }

    /**
     * 普通命令内到所属 Lane 读取风险记录，并保存该持仓键最早前值。
     * @param positionKey 需要保存前值的运行时持仓键
     * @param userId 本次捕获实体所属的用户编号
     */
    public void captureRiskSnapshotBefore(long positionKey, long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        // 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。
        LaneLongCaptures<RiskSnapshotRuntime> captured =
                patchRiskSnapshotsBeforeByLane[state.topology().accountLaneId(userId)];
        if (!captured.containsKey(positionKey)) {
            // 当前实体保存的命令前值，null 表示该实体原来不存在。
            RiskSnapshotRuntime before = state.onLane(userId, /* onLane 回调实际提供的所属账户 Lane。 */ lane -> lane.cold.riskSnapshots.get(positionKey));
            captured.put(positionKey, before);
        }
    }

    /**
     * 检查本方法列出的用户、余额、订单、预留、客户映射、触发及风险缓冲是否非空；当前实现未单独检查持仓前值缓冲，不能据此证明所有缓冲都空。
     */
    public boolean hasCaptured() {
        for (RuntimeUserChangeBuffer /* 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。 */ captured : patchUsersBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (LaneBalancePatches /* 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。 */ captured : patchBalancesBeforeByLane)
            if (captured.size() != 0) return true;
        for (LaneLongCaptures<PatchReservationBefore> /* 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。 */ captured : patchReservationsBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (LaneLongCaptures<PatchOrderBefore> /* 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。 */ captured : patchOrdersBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (LaneClientOrderCaptures /* 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。 */ captured : patchClientOrdersBeforeByLane)
            if (captured.size() != 0) return true;
        for (LaneLongCaptures<CoreTriggerOrderState> /* 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。 */ captured : patchTriggersBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (LaneLongCaptures<RiskSnapshotRuntime> /* 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。 */ captured : patchRiskSnapshotsBeforeByLane)
            if (!captured.isEmpty()) return true;
        return false;
    }

    /**
     * 逐 Lane 清空除余额以外的前值有效项；余额保留到最终发布完成后再清理。
     */
    public void clearNonBalanceImages() {
        for (/* 当前需要捕获、恢复或清理的账户 Lane 数组下标。 */ int laneId = 0; laneId < patchUsersBeforeByLane.length; laneId++) {
            patchUsersBeforeByLane[laneId].clear();
            patchReservationsBeforeByLane[laneId].clear();
            patchOrdersBeforeByLane[laneId].clear();
            patchPositionsBeforeByLane[laneId].clear();
            patchClientOrdersBeforeByLane[laneId].clear();
            patchTriggersBeforeByLane[laneId].clear();
            patchRiskSnapshotsBeforeByLane[laneId].clear();
        }
    }

    /**
     * 逐条使用已经捕获的最终余额后值向实时边界发布，再清空该 Lane 余额补丁；没有后值的条目由补丁入口拒绝发布。
     */
    public void publishAndClearBalances() {
        for (LaneBalancePatches /* 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。 */ captured : patchBalancesBeforeByLane) {
            for (/* 当前连续捕获缓冲的有效下标。 */ int index = 0; index < captured.size(); index++)
                captured.publishAvailableAt(state, index);
            captured.clear();
        }
    }

    /**
     * 优先返回捕获前值，包含已捕获 null；无前值时，当前 Lane 或未启动账户线程可读当前持仓，否则读取 Owner 已交接的变化值。
     * @param positionKey 需要保存前值的运行时持仓键
     */
    public PositionRuntime currentPositionBefore(long positionKey) {
        state.assertOwner();
        for (LaneLongCaptures<PositionRuntime> /* 当前实体所属 Lane 的首次前值捕获缓冲，重复操作保留最早值。 */ captured : patchPositionsBeforeByLane)
            if (captured.containsKey(positionKey)) return captured.get(positionKey);
        if (state.currentLaneCommandScope() != null || !state.accountLanesStarted())
            return state.position(positionKey);
        return state.changedPositionValue(positionKey);
    }

    /**
     * 优先返回已捕获订单前值，共享不存在记录返回 null；尚未捕获时才读取当前运行时订单。
     * @param orderId 需要捕获或查找前值的订单编号
     */
    public OrderRuntime currentOrderBefore(long orderId) {
        state.assertOwner();
        // 跨 Lane 查找到的订单前值记录，null 才表示尚未捕获。
        PatchOrderBefore captured = capturedOrderBefore(orderId);
        return captured == null ? state.order(orderId) : captured.value();
    }

    /**
     * 订单回滚记录，分别保存命令前值与可恢复的原权威引用；二者在原位元数据变更时通过冻结快照分离。
     * @param value 订单命令前的值，null 表示原订单不存在，元数据原位变更前可替换为冻结快照
     * @param pending 捕获时此订单是否属于尚待完成的准入
     * @param authority 需要恢复其内容并重新登记的原 Lane 权威对象，和冻结前值可以不同
     */
    record PatchOrderBefore(OrderRuntime value, boolean pending, OrderRuntime authority) {
        /**
         * 未单独指定恢复权威对象时，让前值本身作为默认权威引用，元数据原位变更捕获可另外指定原对象。
         * @param value 原订单值或不存在标记，作为默认恢复引用
         * @param pending 捕获时是否处于尚待完成的准入
         */
        PatchOrderBefore(OrderRuntime value, boolean pending) { this(value, pending, value); }
    }
    /**
     * 预留命令前值与原待完成标记，回滚时据此恢复原有实体并防止重叠待完成准入。
     * @param value 预留命令前值，null 表示原来不存在
     * @param pending 捕获时是否尚待完成，restoreLane 拒绝覆盖已有待完成预留
     */
    record PatchReservationBefore(ReservationRuntime value, boolean pending) {}

    /**
     * 按 Lane 下标取得该分区当前命令的余额前后值补丁，不构造副本。
     * @param index 账户 Lane 对应的补丁数组下标
     */
    public LaneBalancePatches balancePatch(int index) { return patchBalancesBeforeByLane[index]; }
    /**
     * 返回按拓扑分配的余额补丁分区数。
     */
    public int balancePatchCount() { return patchBalancesBeforeByLane.length; }
}
