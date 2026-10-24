package com.surprising.aeron.service.order.state;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.protocol.CoreAlgoOrderCommand;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.change.LaneCommitDelta;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 算法父单和普通子单之间的账户状态约束。子单在同一 Lane 受父单身份、顺序和数量限制，退休时把成交进度归入父单；父单更新必须保留原意图并单调推进版本和子单列表。 构造约定：只提供算法订单已有状态规则的静态入口，不建立另一套调度器。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeAlgoOrderStateTransitions {

    /**
     * 非 algo- 前缀订单不参与此检查；算法子单解析父单和片序号，核对同用户、币对、方向、可执行状态、下一子单位置和数量，上一子单仍活动时拒绝。
     * @param lane 当前合法持有的账户 Lane，父单及子单都应归属它
     * @param userId 算法父单和新子单共同所属用户编号
     * @param child 本次请求准入的已决策子订单，客户标识编码父单和片序号
     */
    public static void requireChildAdmission(AccountLaneState lane, long userId, ResolvedPlaceOrder child) {
        // 准入子单的客户端标识，algo- 前缀保留给父单与片序号校验。
        String key = child.clientOrderId();
        if (key == null || !key.startsWith("algo-")) return;
        // 客户端标识最后一个连字符位置，用来拆出父单编号和片序号。
        int separator = key.lastIndexOf('-');
        // 从准入子单标识解析的父单编号。
        long id; /* 从客户端标识解析的零起始片序号，需要符合父单当前列表边界。 */ int slice;
        try {
            id = Long.parseLong(key.substring(5, separator));
            slice = Integer.parseInt(key.substring(separator + 1));
        } catch (/* 算法子单标识解析失败的异常，转换为 INVALID_ALGO_CHILD。 */ RuntimeException invalid) {
            throw new CoreStateRejectedException("INVALID_ALGO_CHILD", "invalid algo child identity");
        }
        // 当前账户 Lane 保存的父单，必须与子单用户和币对一致。
        var parent = lane.algoOrder(id);
        if (parent == null || !("algo-" + id + "-" + slice).equals(key)
                || parent.userId() != userId || parent.statusCode() >= 2
                || !parent.instrumentId().equals(child.instrumentId()) || parent.side() != child.side()
                || slice != parent.childOrderIds().size() || parent.pendingClosedChildId() != 0
                || child.quantitySteps() > parent.childQuantitySteps()
                || child.quantitySteps() > parent.quantitySteps() - parent.retiredExecutedQuantitySteps()) {
            throw new CoreStateRejectedException("INVALID_ALGO_CHILD", "algo parent cannot admit this child");
        }
        // 父单当前指向的普通子单，仍非终态时不能准入下一个子单。
        var active = lane.order(parent.currentOrderId());
        if (active != null && !active.status().terminal())
            throw new CoreStateRejectedException("INVALID_ALGO_CHILD", "algo child is still active");
    }

    /**
     * 原结算退休流程在回收子单前调用，核对同 Lane 父单和关联片序号，将该子单成交量并入已退休进度，并记录可能尚未列入父单列表的结束子单编号；不相关或畸形标识直接忽略。
     * @param lane 当前合法持有的账户 Lane，父单及子单都应归属它
     * @param identities 当前产品身份表，用来准备或核对永久币对编号
     * @param child 准备由原结算流程退休的普通子订单，提供其最终累计成交量
     * @param changes 该命令原有 Lane 变更回执，保存父单已退休进度的后状态
     */
    public static void retireChild(AccountLaneState lane, RuntimeIdentityRegistry identities,
                            OrderRuntime child, LaneCommitDelta changes) {
        lane.assertOwner();
        // 待退休子单的客户端标识，非算法标识不参与父单进度归并。
        String client = child.clientOrderId();
        if (client == null || !client.startsWith("algo-")) return;
        // 客户端标识最后一个连字符位置，用来拆出父单编号和片序号。
        int separator = client.lastIndexOf('-');
        if (separator <= 5) return;
        // 从待退休子单标识解析的父单编号。
        final long parentId;
        // 从客户端标识解析的零起始片序号，需要符合父单当前列表边界。
        final int slice;
        try {
            parentId = Long.parseLong(client.substring(5, separator));
            slice = Integer.parseInt(client.substring(separator + 1));
        } catch (/* 退休路径遇到的非数字父单或片序号，本辅助处理直接忽略该非有效关联。 */ NumberFormatException malformed) { return; }
        // 当前账户 Lane 保存的父单，必须与子单用户和币对一致。
        var parent = lane.algoOrder(parentId);
        if (parent == null || parent.userId() != child.userId() || slice < 0
                || !parent.instrumentId().equals(identities.instrumentId(child.symbolId()))) return;
        // 该子单已位于父单列表的对应片位置，编号必须一致。
        boolean linked = slice < parent.childOrderIds().size() && parent.childOrderIds().get(slice) == child.orderId();
        // 子单刚结束但片序号恰好还未追加到父单列表，允许保留为待补关联。
        boolean pending = slice == parent.childOrderIds().size()
                && (parent.pendingClosedChildId() == 0 || parent.pendingClosedChildId() == child.orderId());
        if ((!linked && !pending) || child.executedQuantitySteps() > parent.quantitySteps() - parent.retiredExecutedQuantitySteps()) return;
        // 父单已退休成交量加本次子单累计成交量的精确和。
        long executed = Math.addExact(parent.retiredExecutedQuantitySteps(), child.executedQuantitySteps());
        // 父单归并当前子单后得到的后状态，同时放入 Lane 和本命令变化。
        var updated = parent.withRetiredProgress(executed, pending ? child.orderId() : parent.pendingClosedChildId());
        lane.storeAlgoOrder(updated.algoOrderId(), updated);
        changes.putAlgo(updated.algoOrderId(), updated);
    }

    /** 冷执行入口在当前账户范围读取权威父单，线上入口先按既有路由交给所属 Lane。 */
    public static void upsert(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                              long userId, CoreAlgoOrderCommand command, long clusterTimestamp) {
        if (runtime == null || identities == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime algo order update");
        }
        var current = runtime.algoOrder(command.algoOrderId());
        int symbolId = -1;
        if (command instanceof CoreAlgoOrderCommand.Create create) symbolId = identities.symbolId(create.instrumentId());
        else if (current != null) symbolId = identities.symbolId(current.instrumentId());
        upsert(runtime, userId, command, symbolId, clusterTimestamp);
    }

    /** 新建只接收意图；更新从当前父单保留意图、历史子单和退休成交进度。 */
    public static void upsert(TradingAccountRuntime runtime, long userId, CoreAlgoOrderCommand command,
                              int symbolId, long clusterTimestamp) {
        if (runtime == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid prepared algo update");
        }
        runtime.assertOwner();
        CoreAlgoOrderState current = runtime.algoOrder(command.algoOrderId());
        if (current != null && current.userId() != userId) {
            throw new CoreStateRejectedException("ALGO_ORDER_OWNER_MISMATCH", "algo order belongs to another user");
        }
        CoreAlgoOrderState next;
        switch (command) {
            case CoreAlgoOrderCommand.Create create -> {
                if (current != null) throw new CoreStateRejectedException("INVALID_ALGO_ORDER_CREATE",
                        "algo order already exists");
                if (symbolId < 0 || clusterTimestamp <= 0) throw new IllegalArgumentException("invalid algo creation context");
                if (create.clientAlgoOrderId().isBlank()) {
                    throw new CoreStateRejectedException("INVALID_COMMAND", "clientAlgoOrderId is required");
                }
                if (runtime.hasAlgoClient(userId, create.clientAlgoOrderId())) {
                    throw new CoreStateRejectedException("DUPLICATE_CLIENT_ALGO_ORDER_ID",
                            "clientAlgoOrderId already exists");
                }
                long start = Math.max(create.startAtEpochMillis(), clusterTimestamp);
                next = new CoreAlgoOrderState(create.algoOrderId(), userId, create.clientAlgoOrderId(),
                        create.instrumentId(), create.algoTypeCode(), create.side(), create.priceTicks(),
                        create.quantitySteps(), create.childQuantitySteps(), create.intervalSeconds(),
                                create.durationSeconds(),
                        create.marginMode(), create.positionSide(), create.reduceOnly(),
                                create.timeInForce(),
                        0, 0, "", start, start, clusterTimestamp, clusterTimestamp, 1, java.util.List.of());
            }
            case CoreAlgoOrderCommand.Update update -> {
                if (current == null) throw new CoreStateRejectedException("ENTITY_NOT_FOUND", "algo order is missing");
                if (symbolId < 0) throw new IllegalArgumentException("invalid algo update symbol");
                if (current.terminal() || current.statusCode() == 2 && update.statusCode() < 2) {
                    throw new CoreStateRejectedException("INVALID_ALGO_ORDER_REVISION",
                            "terminal or cancel-requested algo cannot resume execution");
                }
                if (update.expectedRevision() < current.revision()) {
                    throw new CoreStateRejectedException("STALE_ALGO_ORDER_REVISION", "algo order revision is stale");
                }
                if (update.expectedRevision() != current.revision()) {
                    throw new CoreStateRejectedException("INVALID_ALGO_ORDER_REVISION",
                            "algo order revision is not monotonic");
                }
                java.util.List<Long> children = current.childOrderIds();
                long pending = current.pendingClosedChildId();
                long appended = update.appendedChildOrderId();
                if (appended != 0) {
                    var child = runtime.order(appended);
                    if ((child == null && pending != appended)
                            || (child != null && (child.userId() != userId || child.symbolId() != symbolId
                            || !("algo-" + current.algoOrderId() + "-" + children.size()).equals(child.clientOrderId())))) {
                        throw new CoreStateRejectedException("INVALID_ALGO_CHILD", "algo child order is not authoritative");
                    }
                    var appendedChildren = new java.util.ArrayList<>(children);
                    appendedChildren.add(appended);
                    children = appendedChildren;
                    if (pending == appended) pending = 0;
                }
                next = new CoreAlgoOrderState(current.algoOrderId(), current.userId(), current.clientAlgoOrderId(),
                        current.instrumentId(), current.algoTypeCode(), current.side(), current.priceTicks(),
                        current.quantitySteps(), current.childQuantitySteps(), current.intervalSeconds(),
                                current.durationSeconds(),
                        current.marginMode(), current.positionSide(), current.reduceOnly(),
                                current.timeInForce(),
                        update.statusCode(), update.currentOrderId(), update.rejectReason(), current.startAtEpochMillis(),
                        update.nextSliceAtEpochMillis(), current.createdAtEpochMillis(),
                        update.updatedAtEpochMillis(), Math.incrementExact(current.revision()), children,
                        current.retiredExecutedQuantitySteps(), pending);
            }
        }
        runtime.requireRevisionCapacity();
        runtime.putAlgoOrder(next);
        runtime.incrementCommandRevision();
    }
}
