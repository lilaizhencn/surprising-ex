package com.surprising.aeron.service.state.position;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.order.OrderRuntime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 账户 Lane 对一份持仓可继续平仓数量的检查结果。已挂同方向平仓委托占用数量，其中只有只减仓订单进入可撤冲突列表；本对象只计算容量和冲突编号，不直接撤单或更改持仓。
 * @param positionQuantitySteps 目标持仓的绝对数量步数，无持仓时为 0
 * @param requestedQuantitySteps 现有同方向平仓委托剩余量合计，包含普通订单和只减仓订单，可能大于持仓绝对量
 * @param committedQuantitySteps 现有委托实际占用的平仓量，取委托请求合计与持仓绝对量的较小值
 * @param availableQuantitySteps 持仓绝对量扣掉已占用量后，还可供新只减仓委托使用的数量步数
 * @param newestFirst 只减仓冲突候选列表，按提交位置及同位置下订单编号从新到旧排列
 */
public record PositionCloseCapacity(
        long positionQuantitySteps,
        long requestedQuantitySteps,
        long committedQuantitySteps,
        long availableQuantitySteps,
        List<Commitment> newestFirst) {

    /** 先按提交日志位置降序、再按订单编号降序，优先选最新的只减仓委托释放容量。 */
    private static final Comparator<Commitment> NEWEST_FIRST = Comparator.comparingLong(Commitment::corePosition)
            .thenComparingLong(Commitment::orderId).reversed();

    /**
     * 校验数量都非负，已承诺量加剩余量必须等于持仓绝对量；空冲突列表统一为空只读列表，非空列表沿用输入引用，不在这里复制。
     */
    public PositionCloseCapacity {
        if (positionQuantitySteps < 0 || requestedQuantitySteps < 0
                || committedQuantitySteps < 0 || availableQuantitySteps < 0
                || Math.addExact(committedQuantitySteps, availableQuantitySteps) != positionQuantitySteps
                || newestFirst == null) {
            throw new IllegalArgumentException("invalid position close capacity");
        }
        // 空列表统一复用；非空列表沿用调用方提供的引用，不在此制作额外副本。
        newestFirst = newestFirst.isEmpty() ? List.of() : newestFirst;
    }

    /**
     * 所属 Lane 遍历自己的已生效挂单，排除指定订单和未提交预占；累计同用户、合约、持仓腿及平仓方向的剩余量，同时只把 reduceOnly 挂单按最新提交优先整理成冲突候选。
     * @param lane 目标用户所属账户 Lane，只读取自己的活动订单、持仓和预占状态
     * @param userId 本次检查平仓容量的用户编号
     * @param symbolId 本次目标合约的整数编号
     * @param positionKey 该用户目标持仓腿的整数键
     * @param positionSide 需要检查的净持仓、多头腿或空头腿
     * @param closeSide 能平掉该持仓的订单买卖方向
     * @param excludedOrderId 本次检查要排除的原订单编号，例如改单时不重复算自己的占用
     */
    public static PositionCloseCapacity inspectLane(AccountLaneState lane, long userId, int symbolId,
            long positionKey, CorePositionSide positionSide, CoreOrderSide closeSide, long excludedOrderId) {
        // 该 Lane 当前目标持仓，缺失时可平仓总量按 0。
        PositionRuntime position = lane.position(positionKey);
        // 当前带方向持仓取绝对值后的数量步数。
        long positionQuantity = position == null ? 0 : Math.absExact(position.signedQuantitySteps());
        // 已生效同方向平仓订单的剩余数量累计，普通订单也占平仓容量。
        long requestedQuantity = 0;
        // 只在遇到 reduceOnly 订单时分配的冲突候选列表，普通平仓单不加入。
        ArrayList<Commitment> commitments = null;
        // 当前用户的有效订单编号索引，缺失时没有现有委托占用。
        var ids = lane.activeOrderIds(userId);
        if (ids != null) {
            // 当前用户有效订单编号的整数迭代器，逐项读取实际订单校验。
            var iterator = ids.iterator();
            while (iterator.hasNext()) {
                // 本轮有效订单编号，指定排除订单和仍有未提交预占的订单先跳过。
                long orderId = iterator.nextValue();
                if (orderId == excludedOrderId || lane.pendingReservation(orderId)) continue;
                // 本轮实际订单，只有用户、合约、持仓腿、平仓方向和 OPEN 状态都匹配才计入。
                OrderRuntime order = lane.order(orderId);
                if (order == null || order.status() != CoreOrderStatus.OPEN || order.userId() != userId
                        || order.symbolId() != symbolId || order.positionSide() != positionSide
                        || order.side() != closeSide) continue;
                if (order.reduceOnly()) {
                    if (commitments == null) commitments = new ArrayList<>(4);
                    commitments.add(new Commitment(orderId, order.remainingQuantitySteps(), order.clusterPosition()));
                }
                requestedQuantity = Math.addExact(requestedQuantity, order.remainingQuantitySteps());
            }
        }
        if (commitments != null && commitments.size() > 1) {
            commitments.sort(NEWEST_FIRST);
        }
        // 实际被现有委托占掉的平仓容量，最多封顶到持仓绝对数量。
        long committed = Math.min(positionQuantity, requestedQuantity);
        // 按最新优先整理的只减仓候选，零条和单条采用直接只读列表，多条沿用已排好序的列表。
        List<Commitment> sortedCommitments;
        if (commitments == null || commitments.isEmpty()) {
            sortedCommitments = List.of();
        } else if (commitments.size() == 1) {
            sortedCommitments = List.of(commitments.get(0));
        } else {
            sortedCommitments = commitments;
        }
        return new PositionCloseCapacity(positionQuantity, requestedQuantity, committed,
                Math.subtractExact(positionQuantity, committed), sortedCommitments);
    }

    /**
     * 要求新增只减仓数量为正，且不超过已扣现有平仓委托后的可用量，超出则返回平仓容量不足的业务拒绝。
     * @param quantitySteps 新增只减仓请求数量，或冲突候选原订单剩余数量，均要求为正
     */
    public void require(long quantitySteps) {
        if (quantitySteps <= 0) throw new IllegalArgumentException("close quantity must be positive");
        if (quantitySteps > availableQuantitySteps) {
            throw new CoreStateRejectedException("REDUCE_ONLY_CAPACITY_EXCEEDED",
                    "reduce-only open quantity exceeds position capacity");
        }
    }

    /**
     * 计算加入新平仓量后超出的数量，从最新只减仓订单起选择待撤编号，覆盖超额或候选耗尽就停止。这里只选冲突，不执行撤单；候选不足时也可能仍无法满足新单。
     * @param incomingQuantitySteps 本次准备加入的正数平仓数量步数
     */
    public List<Long> conflictsFor(long incomingQuantitySteps) {
        if (incomingQuantitySteps <= 0) {
            throw new IllegalArgumentException("incoming close quantity must be positive");
        }
        // 原委托请求量加新增量相对持仓绝对量的超出部分，非正时不需要撤冲突订单。
        long overflow = Math.subtractExact(Math.addExact(requestedQuantitySteps, incomingQuantitySteps),
                positionQuantitySteps);
        if (overflow <= 0 || newestFirst.isEmpty()) return List.of();

        // 先选出的第一笔待撤订单，只有一笔冲突时避免额外分配列表。
        Long singleConflict = null;
        // 出现第二笔待撤订单后才分配的编号列表，保留从新到旧的选择顺序。
        ArrayList<Long> conflicts = null;
        // 已选冲突订单可释放的剩余数量合计，足够覆盖超额时停止选择。
        long released = 0;
        // 可供选择的只减仓候选数，列表耗尽时也结束，不保证一定覆盖全部超额。
        int size = newestFirst.size();
        for (/* 当前按最新优先检查的冲突候选下标。 */ int i = 0; i < size; i++) {
            // 本轮选择的只减仓挂单及其剩余量，加入待撤编号并累计可释放数量。
            Commitment commitment = newestFirst.get(i);
            if (singleConflict == null) {
                singleConflict = commitment.orderId();
            } else {
                if (conflicts == null) {
                    conflicts = new ArrayList<>(4);
                    conflicts.add(singleConflict);
                }
                conflicts.add(commitment.orderId());
            }
            released = Math.addExact(released, commitment.quantitySteps());
            if (released >= overflow) break;
        }
        if (conflicts == null) {
            return singleConflict == null ? List.of() : List.of(singleConflict);
        }
        return conflicts;
    }

    /**
     * 一笔可作为容量冲突撤销的只减仓挂单，保存剩余数量和提交位置，用于优先选择最新委托。
     * @param orderId 现有只减仓挂单编号，选中冲突后由调用方安排真实撤单
     * @param quantitySteps 新增只减仓请求数量，或冲突候选原订单剩余数量，均要求为正
     * @param corePosition 该挂单提交时的集群日志位置，越大越新，0 表示尚无具体提交位置
     */
    public record Commitment(long orderId, long quantitySteps, long corePosition) {
        /**
         * 要求冲突订单编号和剩余数量为正，提交日志位置非负，确保后续排序和释放数量计算有效。
         */
        public Commitment {
            if (orderId <= 0 || quantitySteps <= 0 || corePosition < 0) {
                throw new IllegalArgumentException("invalid close-capacity commitment");
            }
        }
    }
}
