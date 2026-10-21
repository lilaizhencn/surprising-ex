package com.surprising.aeron.service.state.order;

import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.change.LaneCommitDelta;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.model.CoreAlgoOrderState;

/**
 * 算法父单和普通子单之间的账户状态约束。子单在同一 Lane 受父单身份、顺序和数量限制，退休时把成交进度归入父单；父单更新必须保留原意图并单调推进版本和子单列表。
 */
public final class RuntimeAlgoOrderStateTransitions {

    /**
     * 只提供算法订单已有状态规则的静态入口，不建立另一套调度器。
     */
    private RuntimeAlgoOrderStateTransitions() {
    }

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

    /**
     * 校验基本输入并取得币对稠密身份，再进入已准备身份的父单更新入口。
     * @param runtime 提供父单、子单和版本容量校验的所属运行时
     * @param identities 当前产品身份表，用来准备或核对永久币对编号
     * @param userId 算法父单和新子单共同所属用户编号
     * @param view 外部复制命令携带的父单状态视图，不能覆盖账户维护的退休成交进度
     */
    public static void upsert(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                       long userId, CoreAlgoOrderView view) {
        if (runtime == null || identities == null || view == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime algo order update");
        }
        upsert(runtime, userId, view, identities.symbolId(view.instrumentId()));
    }

    /**
     * 新父单要求客户标识唯一、子单列表空且版本为 1；已有父单保持原意图，版本只能加一并最多追加一个权威子单，取消中或终态不能恢复执行，退休进度沿用 Lane 原值。
     * @param runtime 提供父单、子单和版本容量校验的所属运行时
     * @param userId 算法父单和新子单共同所属用户编号
     * @param view 外部复制命令携带的父单状态视图，不能覆盖账户维护的退休成交进度
     * @param symbolId 本次父单绑定的币对稠密身份，追加子单时核对一致
     */
    public static void upsert(TradingRuntimeState runtime, long userId,
                       CoreAlgoOrderView view, int symbolId) {
        if (runtime == null || view == null || userId <= 0 || symbolId < 0)
            throw new IllegalArgumentException("invalid prepared algo update");
        runtime.assertOwner();
        if (view.userId() != userId) {
            throw new CoreStateRejectedException("ALGO_ORDER_OWNER_MISMATCH", "algo order belongs to another user");
        }
        if (view.clientAlgoOrderId().isBlank()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "clientAlgoOrderId is required");
        }
        // 请求视图转换的新父单状态，最终保留账户维护的退休成交量。
        CoreAlgoOrderState next = CoreAlgoOrderState.from(view);
        // 当前运行时的原父单，不存在时走新建规则。
        CoreAlgoOrderState current = runtime.algoOrder(next.algoOrderId());
        if (current == null) {
            // 该用户原算法客户端标识是否已被其他父单占用。
            boolean duplicateClient = runtime.hasAlgoClient(userId, next.clientAlgoOrderId());
            if (duplicateClient) {
                throw new CoreStateRejectedException("DUPLICATE_CLIENT_ALGO_ORDER_ID",
                        "clientAlgoOrderId already exists");
            }
            if (!next.childOrderIds().isEmpty() || next.revision() != 1) {
                throw new CoreStateRejectedException("INVALID_ALGO_ORDER_CREATE", "new algo order must start empty");
            }
        } else {
            requireSameIntent(current, next);
            if (current.terminal() || current.statusCode() == 2 && next.statusCode() < 2) {
                throw new CoreStateRejectedException("INVALID_ALGO_ORDER_REVISION",
                        "terminal or cancel-requested algo cannot resume execution");
            }
            if (next.revision() <= current.revision()) {
                throw new CoreStateRejectedException("STALE_ALGO_ORDER_REVISION", "algo order revision is stale");
            }
            if (next.revision() != Math.incrementExact(current.revision())
                    || next.childOrderIds().size() < current.childOrderIds().size()
                    || !next.childOrderIds().subList(0, current.childOrderIds().size()).equals(current.childOrderIds())
                    || next.childOrderIds().size() > current.childOrderIds().size() + 1) {
                throw new CoreStateRejectedException("INVALID_ALGO_ORDER_REVISION",
                        "algo order revision is not monotonic");
            }
            if (next.childOrderIds().size() > current.childOrderIds().size()) {
                // 新追加列表最后一项指向的普通子单，必须属于同用户、币对和精确算法片标识。
                OrderRuntime child = runtime.order(next.childOrderIds().getLast());
                if ((child == null && current.pendingClosedChildId() != next.childOrderIds().getLast())
                        || (child != null && (child.userId() != userId || child.symbolId() != symbolId
                        || !("algo-"+current.algoOrderId()+"-"+current.childOrderIds().size()).equals(child.clientOrderId())))) {
                    throw new CoreStateRejectedException("INVALID_ALGO_CHILD",
                            "algo child order is not authoritative");
                }
            }
        }
        if (current != null) {
            // 原父单保留的已结束未补列表子单编号，成功补入列表后清零。
            long pending = current.pendingClosedChildId();
            if (next.childOrderIds().size() > current.childOrderIds().size()
                    && next.childOrderIds().getLast() == pending) pending = 0;
            next = next.withRetiredProgress(current.retiredExecutedQuantitySteps(), pending);
        }
        runtime.requireRevisionCapacity();
        runtime.putAlgoOrder(next);
        runtime.incrementCommandRevision();
    }

    /**
     * 核对父单用户、客户端标识、币对、方向、价格数量、拆单节奏、资金模式、有效期和起始时间等原始意图不可改变。
     * @param left 账户中原父单状态
     * @param right 请求更新的新父单状态，只允许推进生命周期与列表而不能改意图
     */
    private static void requireSameIntent(CoreAlgoOrderState left, CoreAlgoOrderState right) {
        if (left.userId() != right.userId() || !left.clientAlgoOrderId().equals(right.clientAlgoOrderId())
                || !left.instrumentId().equals(right.instrumentId()) || left.algoTypeCode() != right.algoTypeCode()
                || left.side() != right.side() || left.priceTicks() != right.priceTicks()
                || left.quantitySteps() != right.quantitySteps()
                || left.childQuantitySteps() != right.childQuantitySteps()
                || left.intervalSeconds() != right.intervalSeconds() || left.durationSeconds() != right.durationSeconds()
                || left.marginMode() != right.marginMode() || left.positionSide() != right.positionSide()
                || left.reduceOnly() != right.reduceOnly() || left.postOnly() != right.postOnly()
                || left.timeInForce() != right.timeInForce() || left.startAtEpochMillis() != right.startAtEpochMillis()
                || left.createdAtEpochMillis() != right.createdAtEpochMillis()) {
            throw new CoreStateRejectedException("ALGO_ORDER_INTENT_MISMATCH", "algo order intent is immutable");
        }
    }
}
