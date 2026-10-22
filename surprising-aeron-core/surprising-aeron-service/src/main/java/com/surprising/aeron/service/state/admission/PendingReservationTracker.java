package com.surprising.aeron.service.state.admission;

import lombok.RequiredArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.order.ReservationRuntime;
import com.surprising.aeron.service.state.settlement.MatcherSettlementPlan;
import java.util.ArrayList;
import java.util.List;
import org.agrona.collections.Long2LongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

import static com.surprising.aeron.service.state.TradingRuntimeState.*;

/** 未完成订单预留的路由和计数。Owner 保留命令序号、用户归属及批次收据，真实冻结仍在账户 Lane；收到完成事实后只移除对应路由和计数，回退及账户状态由原入口处理。 构造约定：绑定所属运行时，路由计数、账户访问和回滚捕获继续沿用原所有权边界。 参数 owner：提供原账户访问、回滚和变更标记入口的所属运行时 */
@RequiredArgsConstructor
public final class PendingReservationTracker {
    /** 所属运行时，路由在原所有权范围维护，真实冻结仍由各账户 Lane 持有。 */
    final TradingRuntimeState owner;


    /** 单项路径的命令序号到待完成订单编号索引，批次路径另存借用收据。 */
    final PendingReservationSequenceIndex pendingReservationsBySequence =
            new PendingReservationSequenceIndex(4_096);

    /** 单项待完成订单编号到用户编号的路由，0 表示未登记。 */
    final Long2LongHashMap pendingReservationUsers = new Long2LongHashMap(4_096, 0.65f, 0);

    /** 各用户已收集的单项及批次待完成数量，Owner 按完成回执更新。 */
    final LongIntHashMap pendingReservationCountsByUser = new LongIntHashMap(4_096);

    /** 撤销一个 Matcher 计划时复用的用户删除计数缓冲，不是长期业务状态。 */
    private final LongIntHashMap matcherRemovedByUserScratch = new LongIntHashMap(64);

    /**  单项及批次仍待完成的全局路由计数。 供 Core 内部在原所有权或有效读取边界内读取全局路由计数，此方法本身不切换线程。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    int totalPendingReservations;

    /** 按 Core 命令序号保留的批次借用收据，不逐订单复制到 Owner 单项路由表。 */
    final LongObjectHashMap<PendingBatch> batches = new LongObjectHashMap<>();

    /**
     * 一批已准入订单的借用收据，保存原命令数组及剩余预留数。Owner 按完成项递减，归零后移除，必须先移除收据再让原批次归还订单租约。
     */
    public static final class PendingBatch {
        /**  该批所有订单共同所属用户编号。 返回该批所有订单共同所属的用户编号。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        final long userId;
        /** 借用原命令的获准订单数组，收据必须在原数组租约回收前移除。 */
        final OrderRuntime[] orders;
        /**  原借用订单数组的有效项数，完成部分项后仍保持原有效前缀范围。 返回原订单数组中有效前缀的长度。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        final int count;
        /**  批次仍未完成的项数，只有 Owner 按实际 Lane 回执递减。 返回本批仍未由 Lane 完成的预留数量。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        int remaining;
        /**
         * 借用指定用户的原订单数组并记录有效前缀，剩余数初始等于本批准入项数。
         * @param userId 预留或批次所属用户编号，必须与真实账户路由一致
         * @param orders 借用的原批次获准订单数组，收据移除前不得回收其租约
         * @param count 本批有效项数或本次已经完成的项数，按具体入口使用
         */
        PendingBatch(long userId, OrderRuntime[] orders, int count) {
            this.userId = userId; this.orders = orders; this.count = count; this.remaining = count;
        }


    /**
     * 按有效前缀下标借用原批次订单，不复制订单实体。
     * @param index 原批次有效数组前缀内的零起始下标
     */
    public OrderRuntime order(int index) { return orders[index]; }

}

    /**
     * 拒绝与已有批次或单项序号重复登记，保存一次借用收据，并累计用户及全局待完成数量。
     * @param sequence 当前批次或查询索引所归属的 Core 命令序号
     * @param userId 预留或批次所属用户编号，必须与真实账户路由一致
     * @param orders 借用的原批次获准订单数组，收据移除前不得回收其租约
     * @param count 本批有效项数或本次已经完成的项数，按具体入口使用
     */
    public void registerBatch(long sequence, long userId, OrderRuntime[] orders, int count) {
        if (batches.containsKey(sequence) || pendingReservationsBySequence.containsKey(sequence))
            throw new IllegalStateException("batch reservation was registered twice");
        batches.put(sequence, new PendingBatch(userId, orders, count));
        pendingReservationCountsByUser.addToValue(userId, count);
        totalPendingReservations = Math.addExact(totalPendingReservations, count);
    }

    /**
     * 按 Lane 回执递减本批、用户和全局剩余数，归零移除对应收据或用户计数，不能完成超过本批剩余量的项。
     * @param sequence 当前批次或查询索引所归属的 Core 命令序号
     * @param count 本批有效项数或本次已经完成的项数，按具体入口使用
     */
    public void completedBatchItems(long sequence, int count) {
        // 本次正在查找或检查的原批次借用收据，剩余计数和订单租约仍归该命令。
        PendingBatch batch = batches.get(sequence);
        if (batch == null || count == 0) return;
        if (count > batch.remaining) throw new IllegalStateException("batch reservation completion overflow");
        batch.remaining -= count;
        totalPendingReservations = Math.subtractExact(totalPendingReservations, count);
        // 当前用户本次完成扣减后的总剩余数，归零时移除用户计数项。
        int remaining = pendingReservationCountsByUser.addToValue(batch.userId, -count);
        if (remaining == 0) pendingReservationCountsByUser.removeKey(batch.userId);
        if (batch.remaining == 0) batches.removeKey(sequence);
    }

    /**
     * 处理部分拒绝等未进入结算计划的剩余项：在所属 Lane 找仍属当前命令的预留，捕获余额前后并完成，再核对实际数量等于批次剩余数。
     * @param sequence 当前批次或查询索引所归属的 Core 命令序号
     * @param batch 当前命令保留的原批次借用收据，包含所属用户及剩余计数
     */
    private void completeBatchRemainder(long sequence, PendingBatch batch) {
        // 所属 Lane 实际完成的该批剩余项数，必须等于 Owner 收据记录的 remaining。
        int completed = owner.onLane(batch.userId, /* 通过原账户访问范围进入的 Lane，只处理属于本 Lane 的用户和预留。 */ lane -> {
            // 在所属 Lane 扫描本批后实际完成的预留数量。
            int count = 0;
            for (/* 当前检查的原批次订单数组下标，完成过或已不存在的预留跳过。 */ int i = 0; i < batch.count; i++) {
                // 原批次槽中当前保留的获准订单输入，用其编号查询所属 Lane 的真实预留。
                OrderRuntime order = batch.orders[i];
                if (order == null || lane.pendingReservationSequence(order.orderId()) != sequence) continue;
                // 当前 Lane 持有的原冻结记录，用其资产捕获需要的余额回滚前后值。
                ReservationRuntime reservation = lane.reservation(order.orderId());
                owner.accountRollback().captureBalanceBefore(batch.userId, reservation.assetId());
                lane.completePendingReservation(order.orderId(), sequence);
                owner.accountRollback().captureBalanceAfter(lane, batch.userId, reservation.assetId());
                owner.changedOrder(order.orderId());
                owner.changedReservation(order.orderId());
                owner.changedUser(batch.userId);
                owner.markBalancesChanged();
                count++;
            }
            return count;
        });
        if (completed != batch.remaining) throw new IllegalStateException("batch Lane reservation count mismatch");
        completedBatchItems(sequence, completed);
    }

    /**
     * 单笔账户预留完成时交出的原冻结记录引用，用于决定余额变化是否需要标记，不创建新的冻结状态。
     * @param reservation 所属 Lane 读取出的原冻结记录引用，部分辅助完成记录允许为 null，按实际路径处理
     */
    record PendingReservationCompletion(ReservationRuntime reservation) {}

    /**
     * 需要完成的一笔预留的订单与用户路由，只作为本次多笔完成准备使用。
     * @param orderId 当前预留所属的普通订单编号
     * @param userId 预留或批次所属用户编号，必须与真实账户路由一致
     */
    record PendingReservationRef(long orderId, long userId) {
    }

    /**
     * 多笔预留完成预检通过后的订单、用户和原冻结记录交接值，供所属 Lane 完成及回滚捕获使用。
     * @param orderId 当前预留所属的普通订单编号
     * @param userId 预留或批次所属用户编号，必须与真实账户路由一致
     * @param reservation 所属 Lane 读取出的原冻结记录引用，部分辅助完成记录允许为 null，按实际路径处理
     */
    record PendingReservationBatchCompletion(
            long orderId, long userId, ReservationRuntime reservation) {
        /**
         * 检查交接的订单和用户编号均为正数，冻结记录引用由具体读取路径提供。
         */
        PendingReservationBatchCompletion {
            if (orderId <= 0 || userId <= 0) {
                throw new IllegalArgumentException("invalid pending reservation completion");
            }
        }
    }

    /**
     * 捕获回滚前值并在所属 Lane 核对冻结归属、设置待完成序号，成功后登记 Owner 路由及计数，不能重复标记同一预留。
     * @param userId 预留或批次所属用户编号，必须与真实账户路由一致
     * @param orderId 当前预留所属的普通订单编号
     * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
     */
    public void markPendingReservation(long userId, long orderId, long coreSequence) {
        owner.assertOwner();
        owner.accountRollback().captureReservationBefore(orderId);
        if (pendingReservationUsers.containsKey(orderId)) {
            throw new IllegalStateException("reservation is already indexed as pending");
        }
        // 调用方提前用精确加减算出的下一全局路由数，真正更新索引时再采用。
        int nextTotalPendingReservations = Math.addExact(totalPendingReservations, 1);
        owner.onLane(userId, /* 通过原账户访问范围进入的 Lane，只处理属于本 Lane 的用户和预留。 */ lane -> {
            // 当前 Lane 持有的原冻结记录，用其资产捕获需要的余额回滚前后值。
            ReservationRuntime reservation = lane.reservation(orderId);
            if (reservation == null || reservation.userId() != userId) {
                throw new IllegalStateException("pending reservation is missing");
            }
            owner.accountRollback().captureBalanceBefore(userId, reservation.assetId());
            lane.markPendingReservation(orderId, coreSequence);
            owner.accountRollback().captureBalanceAfter(lane, userId, reservation.assetId());
            return null;
        });
        indexPendingReservation(userId, orderId, coreSequence, nextTotalPendingReservations);
    }

    /**
     * 消费已经完成的 Lane 准入订单回执，只登记序号、用户和变化标记，不回读活动账户表，也不重复复制已接管的订单缓冲。
     * @param order 已完成 Lane 准入并交出的订单回执，不能从这里再回读活动账户表
     * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
     */
    public void collectControlReservation(OrderRuntime order, long coreSequence) {
        owner.assertOwner();
        if (coreSequence <= 0 || order == null || order.userId() <= 0)
            throw new IllegalStateException("control reservation receipt is missing");
        // 当前单项、撮合计划或序号索引正在处理的普通订单编号。
        long orderId = order.orderId();
        // 从预留路由取得或按用户聚合的所属用户，未找到时用 0 识别索引缺失。
        long userId = order.userId();
        if (pendingReservationUsers.containsKey(orderId))
            throw new IllegalStateException("control reservation receipt was collected twice");
        indexPendingReservation(userId, orderId, coreSequence, Math.incrementExact(totalPendingReservations));
        // pollControlLanes 已接管原冻结订单缓冲；这里只登记身份路由，不能再向第二个 Owner 缓冲复制相同订单。
        owner.changedOrder(orderId);
        owner.changedReservation(orderId);
        owner.changedUser(userId);
        owner.markBalancesChanged();
    }

    /**
     * 把一笔预留加入序号索引、订单归属和用户计数，再采用调用方已算好的全局总数；真实冻结由原 Lane 维护。
     * @param userId 预留或批次所属用户编号，必须与真实账户路由一致
     * @param orderId 当前预留所属的普通订单编号
     * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
     * @param nextTotalPendingReservations 调用方用精确算术提前算好的更新后全局计数
     */
    public void indexPendingReservation(long userId, long orderId, long coreSequence,
                                         int nextTotalPendingReservations) {
        pendingReservationsBySequence.add(coreSequence, orderId);
        pendingReservationUsers.put(orderId, userId);
        pendingReservationCountsByUser.addToValue(userId, 1);
        totalPendingReservations = nextTotalPendingReservations;
    }

    /**
     * 先捕获用户、订单和冻结回滚前值并校验路由，在所属 Lane 完成预留及余额前后捕获，再标记变化并移除 Owner 路由计数。
     * @param userId 预留或批次所属用户编号，必须与真实账户路由一致
     * @param orderId 当前预留所属的普通订单编号
     * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
     */
    public void completePendingReservation(long userId, long orderId, long coreSequence) {
        owner.assertOwner();
        owner.accountRollback().captureUserBefore(userId);
        owner.accountRollback().captureOrderBefore(orderId);
        owner.accountRollback().captureReservationBefore(orderId);
        // 调用方提前用精确加减算出的下一全局路由数，真正更新索引时再采用。
        int nextTotalPendingReservations = Math.subtractExact(totalPendingReservations, 1);
        if (nextTotalPendingReservations < 0) {
            throw new IllegalStateException("pending reservation counters are inconsistent");
        }
        requirePendingReservationIndex(orderId, coreSequence, userId);
        // 所属 Lane 完成单项后交出的原冻结引用，有冻结才标记余额变化。
        PendingReservationCompletion completion = owner.onLane(userId, /* 实际完成该用户单项预留的 Lane，完成前后捕获余额和客户身份回滚信息。 */ accountLane -> {
            // 当前 Lane 持有的原冻结记录，用其资产捕获需要的余额回滚前后值。
            ReservationRuntime reservation = accountLane.reservation(orderId);
            if (reservation != null) owner.accountRollback().captureBalanceBefore(userId, reservation.assetId());
            accountLane.clientKeysByOrderId().forEach(orderId, /* 当前订单绑定的客户身份键，先捕获其回滚前值再完成预留。 */ clientKey -> owner.accountRollback().captureClientOrderBefore(userId, clientKey));
            accountLane.completePendingReservation(orderId, coreSequence);
            if (reservation != null) owner.accountRollback().captureBalanceAfter(accountLane, userId, reservation.assetId());
            return new PendingReservationCompletion(reservation);
        });
        owner.changedOrder(orderId);
        owner.changedReservation(orderId);
        owner.changedUser(userId);
        if (completion.reservation() != null) owner.markBalancesChanged();
        unindexPendingReservation(orderId, coreSequence, userId, nextTotalPendingReservations);
    }

    /**
     * 按命令完成全部剩余预留：批次走原批次收据，单项直接用标量路径，多项先整体预检再按所属 Lane 完成，最后逐项撤销路由。
     * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
     */
    public void completePendingReservations(long coreSequence) {
        owner.assertOwner();
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        // 本次正在查找或检查的原批次借用收据，剩余计数和订单租约仍归该命令。
        PendingBatch batch = batches.get(coreSequence);
        if (batch != null) { completeBatchRemainder(coreSequence, batch); return; }
        // 命令序号直接保存的首项订单编号，0 表示该序号没有成员。
        long firstOrderId = pendingReservationsBySequence.firstOrderId(coreSequence);
        if (firstOrderId == 0) return;
        // 普通单订单直接走已有标量完成入口，不为一个成员再创建数组和两份临时列表。
        if (!pendingReservationsBySequence.hasAdditional(coreSequence)) {
            // 从预留路由取得或按用户聚合的所属用户，未找到时用 0 识别索引缺失。
            long userId = pendingReservationUsers.getOrDefault(firstOrderId, 0);
            if (userId == 0) throw new IllegalStateException("pending reservation owner is missing");
            completePendingReservation(userId, firstOrderId, coreSequence);
            return;
        }
        // 当前命令多项预留导出的编号数组，单项路径不会分配该数组。
        long[] pending = pendingReservationsBySequence.orderIds(coreSequence);
        if (pending.length == 0) return;
        // 根据 Owner 路由组装的订单与用户引用列表，先整体预检再实际完成。
        List<PendingReservationRef> refs = new ArrayList<>(pending.length);
        for (long /* 当前单项、撮合计划或序号索引正在处理的普通订单编号。 */ orderId : pending) {
            // 从预留路由取得或按用户聚合的所属用户，未找到时用 0 识别索引缺失。
            long userId = pendingReservationUsers.getOrDefault(orderId, 0);
            if (userId == 0) throw new IllegalStateException("pending reservation owner is missing");
            refs.add(new PendingReservationRef(orderId, userId));
        }
        // 本次多项完成的准备列表，包含已核对路由及原冻结引用。
        List<PendingReservationBatchCompletion> completions = preflightPendingReservationCompletions(
                coreSequence, refs);
        owner.executeOwnerSettlements(completions, PendingReservationBatchCompletion::userId, /* 当前回调或逐 Lane 核对正在处理的账户分区编号。 */ laneId -> {
            // 通过原账户访问范围进入的 Lane，只处理属于本 Lane 的用户和预留。
            AccountLaneState lane = owner.currentLaneCommandScope();
            for (PendingReservationBatchCompletion /* 当前 Lane 负责的已预检完成项，其他 Lane 的项在本回调跳过。 */ completion : completions) {
                if (owner.topology().accountLaneId(completion.userId()) != laneId) continue;
                lane.completePendingReservation(completion.orderId(), coreSequence);
                owner.accountRollback().captureBalanceAfter(lane, completion.userId(), completion.reservation().assetId());
            }
            return null;
        });
        for (PendingReservationBatchCompletion /* Lane 工作完成后供 Owner 标记变化并删除路由的一项交接值。 */ completion : completions) {
            owner.changedOrder(completion.orderId());
            owner.changedReservation(completion.orderId());
            owner.changedUser(completion.userId());
            owner.markBalancesChanged();
            // 调用方提前用精确加减算出的下一全局路由数，真正更新索引时再采用。
            int nextTotalPendingReservations = Math.subtractExact(totalPendingReservations, 1);
            unindexPendingReservation(completion.orderId(), coreSequence, completion.userId(),
                    nextTotalPendingReservations);
        }
    }

    /**
     * 实际完成多项前先逐项核对 Owner 与 Lane 索引并捕获回滚前值，提前检查总数不会减成负数，返回只读的本次完成准备列表。
     * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
     * @param refs 本次多笔完成需要校验的订单及用户路由列表
     */
    List<PendingReservationBatchCompletion> preflightPendingReservationCompletions(
            long coreSequence, List<PendingReservationRef> refs) {
        // 预检阶段模拟逐项扣减的全局剩余数，只验证不会为负，不提前修改真实总计数。
        int remainingPendingReservations = totalPendingReservations;
        // 本次多项完成的准备列表，包含已核对路由及原冻结引用。
        List<PendingReservationBatchCompletion> completions = new ArrayList<>(refs.size());
        for (PendingReservationRef /* 当前待预检的订单与用户身份引用，先核对索引和回滚前值。 */ ref : refs) {
            owner.accountRollback().captureUserBefore(ref.userId());
            owner.accountRollback().captureOrderBefore(ref.orderId());
            owner.accountRollback().captureReservationBefore(ref.orderId());
            requirePendingReservationIndex(ref.orderId(), coreSequence, ref.userId());
            // 当前所属 Lane 预检通过后交出的订单、用户和原冻结记录。
            PendingReservationBatchCompletion completion = owner.onLane(ref.userId(), /* 通过原账户访问范围进入的 Lane，只处理属于本 Lane 的用户和预留。 */ lane -> {
                // 当前 Lane 持有的原冻结记录，用其资产捕获需要的余额回滚前后值。
                ReservationRuntime reservation = lane.reservation(ref.orderId());
                if (reservation != null) owner.accountRollback().captureBalanceBefore(ref.userId(), reservation.assetId());
                lane.clientKeysByOrderId().forEach(ref.orderId(),
                        /* 当前订单绑定的客户身份键，先捕获其回滚前值再完成预留。 */ clientKey -> owner.accountRollback().captureClientOrderBefore(ref.userId(), clientKey));
                lane.requirePendingReservationCompletion(ref.orderId(), coreSequence);
                return new PendingReservationBatchCompletion(ref.orderId(), ref.userId(), reservation);
            });
            remainingPendingReservations = Math.subtractExact(remainingPendingReservations, 1);
            if (remainingPendingReservations < 0) {
                throw new IllegalStateException("pending reservation counters are inconsistent");
            }
            completions.add(completion);
        }
        return List.copyOf(completions);
    }

    /**
     * 同时核对订单所属用户和命令序号成员关系，Owner 路由与账户预期不一致就立即失败。
     * @param orderId 当前预留所属的普通订单编号
     * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
     * @param userId 预留或批次所属用户编号，必须与真实账户路由一致
     */
    void requirePendingReservationIndex(long orderId, long coreSequence, long userId) {
        // Owner 单项归属表实际记录的用户，需要与调用方预期用户相同。
        long indexedUserId = pendingReservationUsers.getOrDefault(orderId, 0);
        if (indexedUserId != userId || !pendingReservationsBySequence.contains(coreSequence, orderId)) {
            throw new IllegalStateException("pending reservation index differs from account lane state");
        }
    }

    /**
     * 要求路由一致后删除序号成员和订单归属，将用户数减一并在零时移除，再更新调用方准备的全局总数。
     * @param orderId 当前预留所属的普通订单编号
     * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
     * @param userId 预留或批次所属用户编号，必须与真实账户路由一致
     * @param nextTotalPendingReservations 调用方用精确算术提前算好的更新后全局计数
     */
    public void unindexPendingReservation(long orderId, long coreSequence, long userId,
                                           int nextTotalPendingReservations) {
        requirePendingReservationIndex(orderId, coreSequence, userId);
        pendingReservationsBySequence.remove(coreSequence, orderId);
        pendingReservationUsers.remove(orderId);
        // 删除一项后该用户的剩余路由数，零时移除该用户条目。
        int nextUserCount = Math.subtractExact(pendingReservationCountsByUser.get(userId), 1);
        if (nextUserCount == 0) pendingReservationCountsByUser.removeKey(userId);
        else pendingReservationCountsByUser.put(userId, nextUserCount);
        totalPendingReservations = nextTotalPendingReservations;
    }

    /**
     * 按已经确定的撮合结算计划撤销同序号的 Owner 路由；先按用户合并删除数量，再统一递减用户与全局计数，不重复完成账户资金操作。
     * @param plan Matcher 已确定的结算计划，按其同一 Core 序号成员批量撤销路由
     */
    public void unindexMatcherPendingReservations(MatcherSettlementPlan plan) {
        if (plan == null || !pendingReservationsBySequence.containsKey(plan.coreSequence())) return;
        // 本次撮合计划借用的按用户删除计数缓冲，结束后清空复用。
        LongIntHashMap removedByUser = matcherRemovedByUserScratch;
        removedByUser.clear();
        // 撮合计划中实际命中当前序号路由的总删除项数。
        int removed = 0;
        for (/* 当前扫描撮合计划或批次订单的数组下标，只处理匹配的命令成员。 */ int index = 0; index < plan.orderCount(); index++) {
            // 当前单项、撮合计划或序号索引正在处理的普通订单编号。
            long orderId = plan.orderId(index);
            if (!pendingReservationsBySequence.contains(plan.coreSequence(), orderId)) continue;
            // 从预留路由取得或按用户聚合的所属用户，未找到时用 0 识别索引缺失。
            long userId = pendingReservationUsers.getOrDefault(orderId, 0);
            if (userId == 0) throw new IllegalStateException("matcher pending reservation owner is missing");
            removedByUser.addToValue(userId, 1);
            removed++;
        }
        if (removed == 0) return;
        for (/* 当前扫描撮合计划或批次订单的数组下标，只处理匹配的命令成员。 */ int index = 0; index < plan.orderCount(); index++) {
            // 当前单项、撮合计划或序号索引正在处理的普通订单编号。
            long orderId = plan.orderId(index);
            if (!pendingReservationsBySequence.contains(plan.coreSequence(), orderId)) continue;
            pendingReservationsBySequence.remove(plan.coreSequence(), orderId);
            pendingReservationUsers.remove(orderId);
        }
        removedByUser.forEachKeyValue((/* 从预留路由取得或按用户聚合的所属用户，未找到时用 0 识别索引缺失。 */ userId, /* 当前用户在该撮合计划中需要一次性扣减的路由项数。 */ count) -> {
            // 按用户批量扣减后的剩余数，负数表示路由计数损坏，零时移除用户项。
            int next = Math.subtractExact(pendingReservationCountsByUser.get(userId), count);
            if (next == 0) pendingReservationCountsByUser.removeKey(userId);
            else if (next > 0) pendingReservationCountsByUser.put(userId, next);
            else throw new IllegalStateException("pending reservation counters are inconsistent");
        });
        totalPendingReservations = Math.subtractExact(totalPendingReservations, removed);
        removedByUser.clear();
    }

    /**
     * 有当前 Lane 范围时读取真实待完成标记并禁止跨用户 Lane；Owner 无该范围时只查已登记路由及仍保留的批次订单输入。
     * @param orderId 当前预留所属的普通订单编号
     * @param userId 预留或批次所属用户编号，必须与真实账户路由一致
     */
    public boolean pendingReservation(long orderId, long userId) {
        owner.assertOwner();
        // 当前活动 Lane 命令范围，存在时必须与被查询用户的 Lane 一致。
        AccountLaneState scoped = owner.currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != owner.topology().accountLaneId(userId)) {
                throw new IllegalStateException("pending reservation query crossed its owner lane");
            }
            return scoped.pendingReservation(orderId);
        }
        if (pendingReservationUsers.getOrDefault(orderId, 0) == userId) return true;
        // 批次收据已经保留获准订单数组；Owner 在这里检查原收据，不再维护一张重复的订单到准入序号映射。
        for (PendingBatch /* 本次正在查找或检查的原批次借用收据，剩余计数和订单租约仍归该命令。 */ batch : batches.values()) {
            if (batch.userId != userId) continue;
            for (/* 当前扫描撮合计划或批次订单的数组下标，只处理匹配的命令成员。 */ int index = 0; index < batch.count; index++) {
                // 原批次槽中当前保留的获准订单输入，用其编号查询所属 Lane 的真实预留。
                OrderRuntime order = batch.orders[index];
                if (order != null && order.orderId() == orderId) return true;
            }
        }
        return false;
    }

    /**
     * 通过所属 Lane 的原只读边界查询用户某资产的待完成预留金额，不用 Owner 路由估算资金。
     * @param userId 预留或批次所属用户编号，必须与真实账户路由一致
     * @param assetId 要读取待完成预留金额的资产运行时编号
     */
    public long pendingReservedUnits(long userId, int assetId) {
        owner.assertOwner();
        return owner.onLaneReadOnly(owner.topology().accountLaneId(userId), /* 通过原账户访问范围进入的 Lane，只处理属于本 Lane 的用户和预留。 */ lane -> lane.pendingReservedUnits(userId, assetId));
    }

    /**
     * 在当前 Lane 范围读取其权威用户计数，否则返回 Owner 已收集的该用户路由计数，禁止跨 Lane 读取。
     * @param userId 预留或批次所属用户编号，必须与真实账户路由一致
     */
    public int pendingReservationCount(long userId) {
        owner.assertOwner();
        // 当前活动 Lane 命令范围，存在时必须与被查询用户的 Lane 一致。
        AccountLaneState scoped = owner.currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != owner.topology().accountLaneId(userId)) {
                throw new IllegalStateException("pending reservation count crossed its owner lane");
            }
            return scoped.pendingReservationCount(userId);
        }
        return pendingReservationCountsByUser.get(userId);
    }

    /**
     * 在原所有权要求下返回全部用户已登记的待完成预留总数。
     */
    public int pendingReservationCount() {
        owner.assertOwner();
        return totalPendingReservations;
    }

    /**
     * 在原所有权要求下根据全局计数判断是否仍有待完成预留。
     */
    public boolean hasPendingReservations() {
        owner.assertOwner();
        return totalPendingReservations != 0;
    }

    /**
     * 活动 Lane 未交接给 Owner 时只核对 Owner 路由、批次剩余和用户总数；具备账户访问条件时逐 Lane 比较权威待完成总量。
     */
    public void assertPendingReservationCounts() {
        if (owner.accountLanesStarted() && !owner.ownerLaneAccess) {
            // Owner 单项归属条数加各批剩余项的总数，用于与全局及用户计数交叉核对。
            int tracked = pendingReservationUsers.size();
            for (PendingBatch /* 本次正在查找或检查的原批次借用收据，剩余计数和订单租约仍归该命令。 */ batch : batches.values()) tracked = Math.addExact(tracked, batch.remaining);
            if (tracked != totalPendingReservations
                    || pendingReservationCountsByUser.sum() != totalPendingReservations)
                throw new IllegalStateException("Owner pending reservation routing counts differ");
            return;
        }
        // 逐个进入 Lane 后累计的真实待完成预留数，用于账户可读边界下核对。
        int lanePendingReservations = 0;
        for (/* 当前回调或逐 Lane 核对正在处理的账户分区编号。 */ int laneId = 0; laneId < owner.accountLaneCount(); laneId++) {
            // 本轮要通过原 onLane 边界核对计数的账户 Lane 编号。
            int currentLaneId = laneId;
            lanePendingReservations = Math.addExact(lanePendingReservations,
                    owner.onLane(currentLaneId, AccountLaneState::pendingReservationCount));
        }
        if (lanePendingReservations != totalPendingReservations) {
            throw new IllegalStateException("pending reservation counters differ from account lanes, lane="
                    + lanePendingReservations + ", total=" + totalPendingReservations);
        }
    }

    /**
     * 命令序号到预留订单编号的 primitive 索引。每个序号首项直接存一个 long，多项才建立附加集合；附加项不承诺业务排序，删除首项时从剩余集合提升一项。
     */
    static final class PendingReservationSequenceIndex {
        /** 无成员查询共用的空 long 数组，不得由调用方写入。 */
        static final long[] EMPTY_ORDER_IDS = new long[0];

        /** 每个命令序号直接保存的一笔首项订单编号，避免单项路径创建集合。 */
        final Long2LongHashMap firstOrderBySequence;
        /** 多项命令才创建的其余成员 primitive 集合，不承担业务排序。 */
        final LongObjectHashMap<LongHashSet> additionalOrdersBySequence;

        /**
         * 创建首项的 primitive 序号表与按需附加集合表，0 作为未找到的首项标记。
         * @param initialCapacity 首项 primitive 序号表的初始容量，不是业务数量上限
         */
        PendingReservationSequenceIndex(int initialCapacity) {
            firstOrderBySequence = new Long2LongHashMap(initialCapacity, 0.65f, 0);
            additionalOrdersBySequence = new LongObjectHashMap<>();
        }

        /**
         * 读取该命令序号直接保存的首个订单编号，没有成员返回 0。
         * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
         */
        long firstOrderId(long coreSequence) {
            return firstOrderBySequence.get(coreSequence);
        }

        /**
         * 检查该序号是否还保留首项之外的订单，用来选择单项标量完成路径。
         * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
         */
        boolean hasAdditional(long coreSequence) {
            // 该序号首项之外的集合，存在且非空才属于多项路径。
            LongHashSet additional = additionalOrdersBySequence.get(coreSequence);
            return additional != null && !additional.isEmpty();
        }

        /**
         * 首笔直接存入首项表，多笔才创建附加集合；首项或附加项重复登记都报错，调用方负责传入有效正数身份。
         * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
         * @param orderId 当前预留所属的普通订单编号
         */
        void add(long coreSequence, long orderId) {
            // 命令序号直接保存的首项订单编号，0 表示该序号没有成员。
            long firstOrderId = firstOrderBySequence.get(coreSequence);
            if (firstOrderId == 0) {
                firstOrderBySequence.put(coreSequence, orderId);
                return;
            }
            if (firstOrderId == orderId) {
                throw new IllegalStateException("reservation is already indexed for sequence");
            }
            // 当前命令序号的附加成员集合，单项路径尚未创建时为 null。
            LongHashSet additionalOrderIds = additionalOrdersBySequence.get(coreSequence);
            if (additionalOrderIds == null) {
                additionalOrderIds = new LongHashSet();
                additionalOrdersBySequence.put(coreSequence, additionalOrderIds);
            }
            if (!additionalOrderIds.add(orderId)) {
                throw new IllegalStateException("reservation is already indexed for sequence");
            }
        }

        /**
         * 按首项表判断该命令序号是否有任何待完成成员。
         * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
         */
        boolean containsKey(long coreSequence) {
            return firstOrderBySequence.containsKey(coreSequence);
        }

        /**
         * 先比较非零首项，再查询附加集合，判断指定订单是否属于该命令。
         * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
         * @param orderId 当前预留所属的普通订单编号
         */
        boolean contains(long coreSequence, long orderId) {
            // 命令序号直接保存的首项订单编号，0 表示该序号没有成员。
            long firstOrderId = firstOrderBySequence.get(coreSequence);
            if (firstOrderId == orderId) return orderId != 0;
            // 当前命令序号的附加成员集合，单项路径尚未创建时为 null。
            LongHashSet additionalOrderIds = additionalOrdersBySequence.get(coreSequence);
            return additionalOrderIds != null && additionalOrderIds.contains(orderId);
        }

        /**
         * 导出当前序号成员，空结果共用只读空数组，单项创建一项数组；多项首项在前，其余沿用集合遍历顺序，不进行业务排序。
         * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
         */
        long[] orderIds(long coreSequence) {
            // 命令序号直接保存的首项订单编号，0 表示该序号没有成员。
            long firstOrderId = firstOrderBySequence.get(coreSequence);
            if (firstOrderId == 0) return EMPTY_ORDER_IDS;
            // 当前命令序号的附加成员集合，单项路径尚未创建时为 null。
            LongHashSet additionalOrderIds = additionalOrdersBySequence.get(coreSequence);
            if (additionalOrderIds == null || additionalOrderIds.isEmpty()) {
                return new long[]{firstOrderId};
            }
            // 从附加 primitive 集合导出的编号数组，保持集合遍历顺序而非排序。
            long[] additional = additionalOrderIds.toArray();
            // 首项加附加成员的新导出数组，只在本次多项查询或完成准备中使用。
            long[] orderIds = new long[additional.length + 1];
            orderIds[0] = firstOrderId;
            System.arraycopy(additional, 0, orderIds, 1, additional.length);
            return orderIds;
        }

        /**
         * 删除指定成员；非首项清空时移除附加表，首项删除后无成员则删序号，有剩余则提升其中一笔为首项。
         * @param coreSequence 需要标记、完成或查询成员关系的 Core 命令序号
         * @param orderId 当前预留所属的普通订单编号
         */
        void remove(long coreSequence, long orderId) {
            // 命令序号直接保存的首项订单编号，0 表示该序号没有成员。
            long firstOrderId = firstOrderBySequence.get(coreSequence);
            if (firstOrderId == 0) {
                throw new IllegalStateException("pending reservation sequence is missing");
            }
            // 当前命令序号的附加成员集合，单项路径尚未创建时为 null。
            LongHashSet additionalOrderIds = additionalOrdersBySequence.get(coreSequence);
            if (firstOrderId != orderId) {
                if (additionalOrderIds == null || !additionalOrderIds.remove(orderId)) {
                    throw new IllegalStateException("pending reservation is missing from sequence");
                }
                if (additionalOrderIds.isEmpty()) additionalOrdersBySequence.removeKey(coreSequence);
                return;
            }
            if (additionalOrderIds == null || additionalOrderIds.isEmpty()) {
                firstOrderBySequence.remove(coreSequence);
                return;
            }
            // 附加集合已确认非空，直接用 primitive 迭代器提升一笔首项，无须再创建筛选谓词或适配对象。
            // 附加集合中任取的一笔现存订单，删除首项后提升为新的首项。
            long promotedOrderId = additionalOrderIds.longIterator().next();
            additionalOrderIds.remove(promotedOrderId);
            firstOrderBySequence.put(coreSequence, promotedOrderId);
            if (additionalOrderIds.isEmpty()) additionalOrdersBySequence.removeKey(coreSequence);
        }

        /**
         * 根据首项表判断整个序号成员索引是否为空。
         */
        boolean isEmpty() {
            return firstOrderBySequence.isEmpty();
        }

        /**
         * 清空首项和附加集合两张索引，不执行任何账户资金变化。
         */
        void clear() {
            firstOrderBySequence.clear();
            additionalOrdersBySequence.clear();
        }
    }


    /**
     * 按订单编号直接读单项预留归属用户，未登记时返回 primitive 表的 0 标记。
     * @param key 要读取单项预留归属的订单编号
     */
    public long pendingUser(long key) { return pendingReservationUsers.get(key); }

    /**
     * 判断单项预留归属表是否包含指定订单编号，批次收据另行管理。
     * @param key 要检查单项归属表成员关系的订单编号
     */
    public boolean hasPendingUser(long key) { return pendingReservationUsers.containsKey(key); }
    /**
     * 检查单项订单到用户路由表是否为空，不把它等同于所有批次均已完成。
     */
    public boolean pendingUserIndexEmpty() { return pendingReservationUsers.isEmpty(); }
    /**
     * 按命令序号借用当前批次收据，不存在时为 null。
     * @param key 要读取批次收据的 Core 命令序号
     */
    public PendingBatch batch(long key) { return batches.get(key); }

    /**
     * 判断给定命令序号是否仍保留批次收据。
     * @param key 要检查批次收据成员关系的 Core 命令序号
     */
    public boolean hasBatch(long key) { return batches.containsKey(key); }
    /**
     * 判断单项序号索引是否包含给定命令，批次收据使用独立表。
     * @param sequence 当前批次或查询索引所归属的 Core 命令序号
     */
    public boolean containsSequence(long sequence) { return pendingReservationsBySequence.containsKey(sequence); }
    /**
     * 检查单项序号索引是否为空，批次收据仍需单独检查。
     */
    public boolean sequenceIndexEmpty() { return pendingReservationsBySequence.isEmpty(); }
    /**
     * 导出给定命令在单项序号索引中的成员，沿用首项加附加集合的顺序。
     * @param sequence 当前批次或查询索引所归属的 Core 命令序号
     */
    public long[] pendingOrderIds(long sequence) { return pendingReservationsBySequence.orderIds(sequence); }
    /**
     * 清空恢复相关的单项、批次和用户路由并把总数归零，再按当前运行条件核对计数，调用方负责处于允许重建的边界。
     */
    public void clearRestoredPendingReservations() {
        pendingReservationsBySequence.clear(); batches.clear(); pendingReservationUsers.clear();
        pendingReservationCountsByUser.clear(); totalPendingReservations = 0; assertPendingReservationCounts();
    }

}
