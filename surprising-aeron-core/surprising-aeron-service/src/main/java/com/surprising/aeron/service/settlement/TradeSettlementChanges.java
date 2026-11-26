package com.surprising.aeron.service.settlement;

import com.surprising.aeron.service.order.ReplaceOrderCheck;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountTransactionRollback;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.OrderResultTarget;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.treasury.FundsTotal;
import com.surprising.aeron.service.treasury.SystemFundsChange;
import com.surprising.aeron.service.treasury.SystemFunds;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/** 下单步骤 4.2–4.6 的可复用结算事件；ready 还等待发布方交出租约，清理前须交出 changes。 */

/** 下单步骤 4.4 与 4.6 的结算变化缓冲。完成交接后供财务核对和 Owner 收集。权威账户仍在 AccountPartition，缓冲只在当前事件租约内有效，清空保留容量。 */
public final class TradeSettlementChanges {
    private static final AccountPartitionCommit.Delta EMPTY_LANE_DELTA = new AccountPartitionCommit.Delta();
    /** 未激活 Lane 共用的空余额捕获哨兵，不可写入业务变化。 */
    private static final AccountTransactionRollback.BalancePatches EMPTY_BALANCE_PATCHES = new AccountTransactionRollback.BalancePatches();
    /** 未激活 Lane 共用的空资金增量哨兵，不可累加金额。 */
    private static final FundsTotal EMPTY_FUNDS_DELTA = new FundsTotal();
    /** 未激活 Lane 共用的空用户修订哨兵，不可写入用户增量。 */
    private static final LongLongHashMap EMPTY_USER_REVISIONS = new LongLongHashMap();
    /** Owner 收集时据此释放已发布身份。返回本代是否采用直达持仓身份交接，供 Owner 选择身份释放方式。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    boolean directPositionIdentities;
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    boolean inUse;
    @Accessors(fluent = true, chain = false)
    @Getter
    final int ringIndex;
    /** 由持有租约的调用方直接设置参与位图。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    long activeLaneMask;
    final AccountPartitionCommit.Delta[] laneDeltas;
    /** 按 Lane 保存的余额前后值，供回滚及资金差额核对。 */
    public final AccountTransactionRollback.BalancePatches[] balancePatches;
    final FundsTotal[] laneFundsDeltas;
    final LongLongHashMap[] userRevisionDeltas;

    /** 按账户 Lane 数创建缓冲入口，默认不关联固定环槽。 */
    public TradeSettlementChanges(int laneCount) {
        this(laneCount, -1);
    }

    /** 保存环槽编号并创建各 Lane 的缓冲列，未参与的 Lane 先共用只读空哨兵，真正使用时再分配独立缓冲。 */
    public TradeSettlementChanges(int laneCount, int ringIndex) {
        this.ringIndex = ringIndex;
        laneDeltas = new AccountPartitionCommit.Delta[laneCount];
        balancePatches = new AccountTransactionRollback.BalancePatches[laneCount];
        laneFundsDeltas = new FundsTotal[laneCount];
        userRevisionDeltas = new LongLongHashMap[laneCount];
        java.util.Arrays.fill(laneDeltas, EMPTY_LANE_DELTA);
        java.util.Arrays.fill(balancePatches, EMPTY_BALANCE_PATCHES);
        java.util.Arrays.fill(laneFundsDeltas, EMPTY_FUNDS_DELTA);
        java.util.Arrays.fill(userRevisionDeltas, EMPTY_USER_REVISIONS);
    }

    /** 为位图内的 Lane 把空哨兵换成独立可写缓冲。 */
    public void ensureActiveLanes(long laneMask) {
        while (laneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(laneMask);
            laneMask &= laneMask - 1;
            if (laneDeltas[laneId] == EMPTY_LANE_DELTA) laneDeltas[laneId] = new AccountPartitionCommit.Delta();
            if (balancePatches[laneId] == EMPTY_BALANCE_PATCHES) balancePatches[laneId] = new AccountTransactionRollback.BalancePatches();
            if (laneFundsDeltas[laneId] == EMPTY_FUNDS_DELTA) laneFundsDeltas[laneId] = new FundsTotal();
            if (userRevisionDeltas[laneId] == EMPTY_USER_REVISIONS) userRevisionDeltas[laneId] = new LongLongHashMap();
        }
    }

    /** 把用户在本代结算中的修订增量累加到所属 Lane 缓冲。 */
    public void addUserRevision(int laneId, long userId, long count) {
        userRevisionDeltas[laneId].addToValue(userId, count);
    }

    /** 在所属 Lane 应用已聚合的用户修订增量并写入发布变化。 */
    void applyUserRevisions(int laneId, AccountPartition lane) {
        LongLongHashMap deltas = userRevisionDeltas[laneId];
        if (deltas.isEmpty()) return;
        lane.applyUserRevisions(deltas, laneDeltas[laneId]);
        deltas.clear();
    }

    /** 为指定 Lane 提前准备准入与终态槽容量。 */
    public void ensureOrderCheckCapacity(int laneId, int expectedOrders) {
        if (expectedOrders <= 0) return;
        ensureActiveLanes(1L << laneId);
        laneDeltas[laneId].ensureOrderCheckCapacity(expectedOrders);
        laneDeltas[laneId].ensureTerminalCapacity(expectedOrders);
    }

    /** 按本次实际路由登记参与 Lane，并为这些 Lane 提前准备订单及终态槽容量。 */
    public void ensureOrderCapacity(int expectedOrders, long laneMask) {
        if (expectedOrders <= 0) return;
        activeLaneMask |= laneMask;
        ensureActiveLanes(laneMask);
        while (laneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(laneMask);
            laneMask &= laneMask - 1;
            laneDeltas[laneId].ensureOrderCapacity(expectedOrders);
            laneDeltas[laneId].ensureTerminalCapacity(expectedOrders);
        }
    }

    /** 下单步骤 4.4 的账户收尾：应用修订、记录终态，预留耗尽后移除可退休订单并释放客户身份。 */
    public void prepareLaneTerminal(int laneId, IdIndex identities, AccountPartition lane, long sequence) {
        applyUserRevisions(laneId, lane);
        AccountPartitionCommit.Delta changes = laneDeltas[laneId];
        for (int index = 0; index < changes.orders().size(); index++) {
            long orderId = changes.orders().keyAt(index);
            Order order = changes.orders().valueAt(index);
            if (order == null || !order.status().terminal()) continue;
            changes.recordTerminalOrder(order);
            OrderFundsReservation reservation = lane.reservation(orderId);
            if (reservation != null && reservation.reservedUnits() != 0) continue;
            if (reservation != null) {
                lane.removeReservationValue(orderId);
                lane.removeReservationId(order.userId(), orderId);
                changes.removeReservationRoute(orderId);
            }
            if (order.status() == CoreOrderStatus.REJECTED) continue;
            lane.retireAlgoChild(identities, order, changes);
            Order removed = lane.removeOrder(orderId);
            if (removed != null) {
                changes.removeOrderRoute(orderId);
                if (removed.retireForReuse(sequence)) changes.retiredOrder(removed);
            }
            lane.releaseOrderClientIdentities(identities, orderId, order.userId());
            lane.removeClientOrdersForOrder(order.userId(), orderId);
        }
        for (int positionIndex = 0; positionIndex < changes.positions().size(); positionIndex++) {
            Position position = changes.positions().valueAt(positionIndex);
            if (position != null && position.signedQuantitySteps() == 0) {
                LongHashSet ids = lane.triggerIds(position.userId());
                if (ids != null && !ids.isEmpty()) {
                    String instrumentId = identities.instrumentId(position.symbolId());
                    var iterator = ids.longIterator();
                    while (iterator.hasNext()) {
                        long id = iterator.next();
                        CoreTriggerOrderState trigger = lane.triggerOrder(id);
                        if (trigger == null || trigger.status() != CoreTriggerOrderStatus.PENDING
                                || trigger.positionSide() != position.positionSide()
                                || trigger.marginMode() != position.marginMode()
                                || !instrumentId.equals(trigger.instrumentId())) continue;
                        CoreTriggerOrderState canceled = trigger.toPendingCancellation();
                        lane.putTrigger(canceled);
                        changes.putTrigger(id, canceled);
                        changes.closedTriggerCount(Math.incrementExact(changes.closedTriggerCount()));
                    }
                }
            }
        }
        changes.preparePositionRetirement(lane);
        changes.preparePublication();
        AccountBook.prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
    }

    /** 从指定 Lane 已捕获的余额前后值准备准入资金增量，不在这里执行完整订单终态清理。 */
    public void prepareOrderCheckLane(int laneId) {
        AccountBook.prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
    }

    /** 遍历全部已经分配的余额缓冲，把前值合并到目标。 */
    public void copyBalanceBeforeTo(AccountTransactionRollback.BalancePatches target) {
        if (target == null) throw new IllegalArgumentException("balance capture target is required");
        for (int laneId = 0; laneId < balancePatches.length; laneId++) {
            AccountTransactionRollback.BalancePatches source = balancePatches[laneId];
            if (source == EMPTY_BALANCE_PATCHES) continue;
            target.mergeBefore(source);
        }
    }

    /** 按调用方给出的 Lane 位图，把各 Lane 已准备的资金增量加入目标累加器，不清空源。 */
    public void appendFundsDelta(long laneMask, FundsTotal target) {
        if (target == null) throw new IllegalArgumentException("funds accumulator is required");
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            target.add(laneFundsDeltas[laneId]);
        }
    }

    /** 把参与 Lane 的待退休订单交回对应账户的回收缓存。 */
    public void returnRetiredOrders(AccountBook accountBook) {
        long lanes = activeLaneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            laneDeltas[laneId].returnRetiredOrders(accountBook.accountLane(laneId));
        }
    }

    /** 清除参与 Lane 的修订、变化、余额与资金缓冲并重置参与位图和持仓身份标记。 */
    public void clear() {
        directPositionIdentities = false;
        long lanes = activeLaneMask;
        while (lanes != 0) {
            int lane = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            userRevisionDeltas[lane].clear();
            laneDeltas[lane].clear();
            balancePatches[lane].clear();
            laneFundsDeltas[lane].clear();
        }
        activeLaneMask = 0;
    }

    /** 借用指定 Lane 的实体与终态变化缓冲，不复制内容。 */
    public AccountPartitionCommit.Delta laneDelta(int index) { return laneDeltas[index]; }

    /** 借用指定 Lane 的余额前后值缓冲，不复制内容。 */
    public AccountTransactionRollback.BalancePatches balancePatch(int index) { return balancePatches[index]; }
    /** 返回按拓扑保留的余额缓冲列数，不表示本代活跃 Lane 数。 */
    public int balancePatchCount() { return balancePatches.length; }
}

