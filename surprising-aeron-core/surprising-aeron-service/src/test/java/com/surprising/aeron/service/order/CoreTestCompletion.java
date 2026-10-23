package com.surprising.aeron.service.order;

import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.service.core.TradingCoreRuntime;

/**
 * 测试侧把真实异步协议入口推进成便于断言的同步结果。查询仍走实际 Lane 或 Matcher 读取，不直接借 Owner 表；轮询设置局部截止，但嵌套等待各有期限，不把它当成生产线程的等待策略。applyAsynchronously 名称指进入异步作用域，方法本身会阻塞推进。
 */
public final class CoreTestCompletion {
    /**
     * 走真实订单查询，ENTITY_NOT_FOUND 返回空，其他响应要求 OK 后解码订单视图。
     * @param owner 当前由测试线程拥有的交易运行时
     * @param userId 协议查询对应的用户身份
     * @param id 目标订单、触发单或算法单身份
     */
    public static com.surprising.aeron.protocol.CoreOrderStateView orderState(
            TradingCoreRuntime owner, long userId, long id) {
        // 当前协议入口或续步返回的可空响应。
        var response = querySynchronously(owner, query(owner,
                com.surprising.aeron.protocol.CoreMessageType.ORDER_STATE_QUERY, userId,
                com.surprising.aeron.protocol.TradingCommandCodec.encodeOrderStateQuery(id)));
        if (response.resultCode() == com.surprising.aeron.protocol.CoreResultCode.ENTITY_NOT_FOUND) return null;
        requireQuerySuccess(response);
        return com.surprising.aeron.protocol.CoreStateQueryCodec.decodeOrderState(response.data());
    }

    /**
     * 查询指定用户和触发单身份的一条结果，要求 OK，列表空返回空否则第一项。
     * @param owner 当前由测试线程拥有的交易运行时
     * @param userId 协议查询对应的用户身份
     * @param id 目标订单、触发单或算法单身份
     */
    public static com.surprising.aeron.protocol.CoreTriggerOrderStateView triggerState(
            TradingCoreRuntime owner, long userId, long id) {
        // 当前协议入口或续步返回的可空响应。
        var response = querySynchronously(owner, query(owner,
                com.surprising.aeron.protocol.CoreMessageType.TRIGGER_ORDER_QUERY, userId,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeQuery(
                        new com.surprising.aeron.protocol.CoreTriggerOrderQuery(id, "", 0, 1))));
        requireQuerySuccess(response);
        // 查询解码后的结果列表，只请求首个目标项。
        var values = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeList(response.data());
        return values.isEmpty() ? null : values.getFirst();
    }

    /**
     * 查询指定用户和算法单身份的一条结果，要求 OK 后取首项或空。
     * @param owner 当前由测试线程拥有的交易运行时
     * @param userId 协议查询对应的用户身份
     * @param id 目标订单、触发单或算法单身份
     */
    public static com.surprising.aeron.protocol.CoreAlgoOrderView algoState(
            TradingCoreRuntime owner, long userId, long id) {
        // 当前协议入口或续步返回的可空响应。
        var response = querySynchronously(owner, query(owner,
                com.surprising.aeron.protocol.CoreMessageType.ALGO_ORDER_QUERY, userId,
                com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeQuery(userId, id, "", 0, 1)));
        requireQuerySuccess(response);
        // 查询解码后的结果列表，只请求首个目标项。
        var values = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decodeList(response.data());
        return values.isEmpty() ? null : values.getFirst();
    }

    /**
     * 用当前产品线、Cluster 时间和 Operations 来源生成随机 UUID 查询消息，来源实例/序号为零。
     * @param owner 当前由测试线程拥有的交易运行时
     * @param type 要构造的状态查询消息类型
     * @param userId 协议查询对应的用户身份
     * @param payload 已编码查询负载
     */
    private static com.surprising.aeron.protocol.CoreMessage query(TradingCoreRuntime owner,
            com.surprising.aeron.protocol.CoreMessageType type, long userId, byte[] payload) {
        return new com.surprising.aeron.protocol.CoreMessage(
                com.surprising.aeron.protocol.CoreMessageHeader.query(type, java.util.UUID.randomUUID(),
                        owner.productLine(), com.surprising.aeron.protocol.CommandSource.OPERATIONS,
                        0, 0, userId, owner.currentClusterTimestamp(), 0), payload);
    }

    /**
     * 要求查询状态恰好为 OK，失败以结果码抛测试断言错误。
     * @param response 需要检查为 OK 的查询响应
     */
    private static void requireQuerySuccess(CoreResponse response) {
        if (response.status() != com.surprising.aeron.protocol.ResponseStatus.OK)
            throw new AssertionError("state query failed: " + response.resultCode());
    }

    /**
     * 构造用户查询并走同步测试查询完成器，要求 OK 后解码用户视图，不从 Owner 的账户表直接取值。
     * @param owner 当前由测试线程拥有的交易运行时
     * @param userId 协议查询对应的用户身份
     */
    public static com.surprising.aeron.protocol.CoreUserStateView userState(TradingCoreRuntime owner, long userId) {
        // 当前用户状态查询的完整协议消息。
        var request = new com.surprising.aeron.protocol.CoreMessage(
                com.surprising.aeron.protocol.CoreMessageHeader.query(com.surprising.aeron.protocol.CoreMessageType.USER_STATE_QUERY,
                        java.util.UUID.randomUUID(), owner.productLine(), com.surprising.aeron.protocol.CommandSource.OPERATIONS,
                        0, 0, userId, owner.currentClusterTimestamp(), 0), new byte[0]);
        // 当前协议入口或续步返回的可空响应。
        CoreResponse response = querySynchronously(owner, request);
        if (response.status() != com.surprising.aeron.protocol.ResponseStatus.OK)
            throw new AssertionError("user query failed: " + response.resultCode());
        return com.surprising.aeron.protocol.CoreStateQueryCodec.decodeUserState(response.data());
    }

    /**
     * 先按异步作用域测试入口执行，若查询仍 MATCHING_PENDING，就按 UUID 取查询序号并最多五秒轮询结果，期间检查运行时健康。
     * @param owner 当前由测试线程拥有的交易运行时
     * @param request 需要执行的完整查询消息
     */
    public static CoreResponse querySynchronously(TradingCoreRuntime owner, com.surprising.aeron.protocol.CoreMessage request) {
        // 当前协议入口或续步返回的可空响应。
        CoreResponse response = applyAsynchronously(owner, request);
        if (response.resultCode() == com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING) {
            // 该异步查询在查询协作器登记的序号。
            long id = owner.bookQueries.querySequence(request.header().commandId());
            // 当前辅助阶段的 nanoTime 截止点，不覆盖内部所有嵌套等待。
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while ((response = owner.bookQueries.takeQueryResult(id)) == null) {
                owner.assertHealthy();
                if (System.nanoTime() >= deadline) throw new AssertionError("user query timeout");
                Thread.onSpinWait();
            }
        }
        return response;
    }

    /**
     * 以消息提交时间和源序号作为测试 Cluster 时间/位置，调用完整异步作用域入口。
     * @param owner 当前由测试线程拥有的交易运行时
     * @param message 要进入异步作用域执行的完整命令或查询
     */
    public static CoreResponse applyAsynchronously(TradingCoreRuntime owner,
            com.surprising.aeron.protocol.CoreMessage message) {
        return applyAsynchronously(owner, message, message.header().submittedAtEpochMillis(),
                message.header().sourceSequence());
    }

    /**
     * 激活运行时并准备入口路由，必要时等 Owner Lane 读取权限，进入异步命令作用域执行；轮询直接工作后继续推动整个已链接待撮合队列到空，保存目标命令响应。设置十秒局部截止，finally 退出作用域并释放读取权限。
     * @param owner 当前由测试线程拥有的交易运行时
     * @param message 要进入异步作用域执行的完整命令或查询
     * @param timestamp 测试传入的 Cluster 业务时间
     * @param position 测试传入的 Cluster 日志位置
     */
    public static CoreResponse applyAsynchronously(TradingCoreRuntime owner,
            com.surprising.aeron.protocol.CoreMessage message, long timestamp, long position) {
        owner.activate();
        // 当前辅助阶段的 nanoTime 截止点，不覆盖内部所有嵌套等待。
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        // 只为本次入口准备路由及解码的临时命令窗口。
        var window = new ClusterCommandWindow();
        // 入口是否可独立推进，无需为准备阶段读取 Lane 状态。
        boolean independent = owner.prepareClusterPipelineScope(message, window);
        while (!independent && owner.requiresOwnerLaneAccessForPreparation(message)
                && !owner.runtimeState().tryAcquireOwnerLaneAccess()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Lane handoff timeout");
            Thread.onSpinWait();
        }
        owner.runtimeState().enterAsynchronousCommandScope();
        try {
            // 当前协议入口或续步返回的可空响应。
            CoreResponse response = owner.applyDecodedCommand(message, timestamp, position, window.decodedIfPresent(message), independent);
            while (owner.directCommand.active()) {
                response = owner.directCommand.poll();
                if (System.nanoTime() >= deadline) throw new AssertionError("direct command timeout");
                Thread.onSpinWait();
            }
            // 当前命令 UUID 对应的待撮合序号。
            long requestedSequence = owner.matchingSequence(message.header().commandId());
            // 保存目标响应的单元素数组，供完成回调原位写入。
            CoreResponse[] completed = {response};
            while (owner.pendingMatching().firstSequence() != 0) {
                owner.commits.commitReadyMatching(64, timestamp, position, false,
                        (/* 当前回调完成或主循环准备推进的 Core 序号。 */ sequence, /* 当前完成回调或事实轮询取得的响应/撮合结果。 */ result) -> { if (sequence == requestedSequence) completed[0] = result; });
                if (System.nanoTime() >= deadline) throw new AssertionError("matching command timeout " + message.header().messageType()
                        + " independent=" + independent + " requested=" + requestedSequence
                        + " first=" + owner.pendingMatching().firstSequence()
                        + " control=" + owner.commits.controlPending(requestedSequence)
                        + " completion=" + owner.pendingMatching().required(requestedSequence).hasMatchingCompletion()
                        + " result=" + owner.pendingMatching().required(requestedSequence).matchingResult()
                        + " submitted=" + owner.pendingMatching().get(requestedSequence).isMatchingSubmitted());
                Thread.onSpinWait();
            }
            return completed[0];
        } finally {
            owner.runtimeState().exitAsynchronousCommandScope();
            owner.runtimeState().releaseOwnerLaneAccess();
        }
    }

    /**
     * 测试线程要求 Owner 身份，推进拒绝、批次准入、Matcher 及 Lane 续步直到指定命令有终态；请求序号零则清空可推进工作并返回空。外层设置十秒截止，内部 await 另有期限，因此不是严格统一十秒预算。
     * @param owner 当前由测试线程拥有的交易运行时
     * @param requestedSequence 准备等待的 Core 序号，零表示推进现有工作而不要求特定终态
     * @param clusterTimestamp 完成推进使用的非负 Cluster 业务时间
     * @param clusterPosition 完成推进使用的非负 Cluster 日志位置
     */
    public static CoreResponse completeMatchingSynchronously(TradingCoreRuntime owner, long requestedSequence,
                                               long clusterTimestamp,
                                               long clusterPosition) {
        owner.assertOwner();
        if (requestedSequence < 0 || clusterTimestamp < 0 || clusterPosition < 0) {
            throw new IllegalArgumentException("invalid synchronous matching fence");
        }
        // 指定 Core 命令最终返回的终态响应，尚未完成时为空。
        CoreResponse requestedResponse = null;
        // 当前辅助阶段的 nanoTime 截止点，不覆盖内部所有嵌套等待。
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        // 保留的局部计数初值，当前方法没有读取或递增它。
        int loop = 0;
        while (true) {
            owner.runtimeState().assertAccountLanesHealthy();
            owner.commits.advanceMatchingProgress(clusterTimestamp, clusterPosition, Long.MAX_VALUE);
            owner.matchingProgress.drainMatchingCompletions();
            // 当前回调完成或主循环准备推进的 Core 序号。
            long sequence = requestedSequence != 0 && owner.pendingMatching().contains(requestedSequence)
                    ? requestedSequence : owner.pendingMatching().firstSequence();
            if (sequence == 0) break;
            owner.matchingProgress.progressPlaceBatchAdmissions();
            // 当前协议入口或续步返回的可空响应。
            CoreResponse response;
            if (owner.matchingFlow.hasPendingMatchingRejection(sequence)) {
                response = owner.commits.completeRejectedMatching(sequence);
            } else {
                // 当前等待的命令槽，可能已经拥有批次或结算事件。
                CommandSlot pending = owner.pendingMatching().get(sequence);
                // 选取的批次、普通或直达撮合事实，缺失时尝试等待。
                com.surprising.aeron.service.matcher.MatchingResult matching =
                        pending != null && pending.orderBatch != null && (pending.orderBatch.itemSettlementEvent != null
                                || pending.orderBatch.itemAdmission != null && pending.orderBatch.activated())
                                ? pending.orderBatch.lastMatchingResult
                                : pending != null && (pending.settlementEvent() != null
                                || pending.orderBatch != null && pending.orderBatch.laneCommitEvent != null)
                                ? owner.pendingMatching().required(sequence).matchingResult()
                                : awaitMatchingResult(owner, sequence);
                if (matching == null) matching = awaitMatchingResult(owner, sequence);
                if (matching == null && owner.matchingFlow.hasPendingMatchingRejection(sequence)) {
                    response = owner.commits.completeRejectedMatching(sequence);
                } else if (matching == null) {
                    throw new IllegalStateException(
                            "matcher did not complete sequence " + sequence);
                } else {
                    response = owner.commits.completeMatching(sequence, matching, clusterTimestamp, clusterPosition);
                }

            }
            if (response != null && sequence == requestedSequence) {
                requestedResponse = response;
                break;
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("completeMatchingSynchronously timeout for sequence " + requestedSequence);
            }
            Thread.onSpinWait();
        }
        if (requestedSequence != 0 && requestedResponse == null) {
            throw new IllegalStateException(
                    "synchronous matcher did not produce a terminal response for sequence "
                            + requestedSequence);
        }
        return requestedResponse;
    }

    /**
     * 使用运行时默认 Matcher 等待时限调用完整等待辅助器。
     * @param state 需要等待撮合事实的测试运行时
     * @param sequence 需要等待事实的完整 Core 序号
     */
    public static com.surprising.aeron.service.matcher.MatchingResult awaitMatchingResult(
            TradingCoreRuntime state, long sequence) {
        return awaitMatchingResult(state, sequence, TradingCoreRuntime.MATCHING_AWAIT_TIMEOUT_NANOS);
    }

    /**
     * 入口已有致命故障或时限非正返回空；先推进未完成准入，再激活待批次、收集普通或直达事实，先自旋后短 park。拒绝、超时或命令不再存在均可能返回空，不能把空值一律解释为原生拒单。
     * @param state 需要等待撮合事实的测试运行时
     * @param sequence 需要等待事实的完整 Core 序号
     * @param timeoutNanos 本次等待撮合事实的纳秒期限，非正直接返回空
     */
    public static com.surprising.aeron.service.matcher.MatchingResult awaitMatchingResult(
            TradingCoreRuntime state, long sequence, long timeoutNanos) {
        if (state.fatalFailure() != null) return null;
        if (timeoutNanos <= 0) return null;
        // 当前辅助阶段的 nanoTime 截止点，不覆盖内部所有嵌套等待。
        long deadline = System.nanoTime() + timeoutNanos;
        // 当前等待的命令槽，可能已经拥有批次或结算事件。
        CommandSlot pending = state.pendingMatching().get(sequence);
        while (pending != null && state.matchingFlow.placeAdmissionOutstanding(pending)
                && !state.matchingFlow.hasPendingMatchingRejection(sequence) && System.nanoTime() < deadline) {
            state.matchingProgress.progressPlaceBatchAdmissions();
            if (state.matchingFlow.placeAdmissionOutstanding(pending)) Thread.onSpinWait();
        }
        if (state.matchingFlow.hasPendingMatchingRejection(sequence)) return null;
        // 连续空探查次数，达到阈值后从自旋转为短暂 park。
        int idle = 0;
        while (state.pendingMatching().contains(sequence) && System.nanoTime() < deadline) {
            // 当前最早待命令，尚未激活的批次先通过现有业务入口激活。
            CommandSlot head = state.pendingMatching().get(state.pendingMatching().firstSequence());
            if (head != null && head.orderBatch != null && !head.orderBatch.activated())
                state.batches().activateOrderBatch(head.orderBatch, head, true);
            state.matchingProgress.drainMatchingCompletions();
            if (state.matchingFlow.hasPendingMatchingRejection(sequence)) return null;
            // 所请求序号的严格占用槽，读取现有事实或取普通完成。
            CommandSlot context = state.pendingMatching().required(sequence);
            // 当前完成回调或事实轮询取得的响应/撮合结果。
            com.surprising.aeron.service.matcher.MatchingResult result = context.matchingResult();
            if (result == null) result = context.takeMatchingCompletion();
            if (result == null) {
                // 当前仍在队中的请求槽，用于选择直达事件。
                var active = state.pendingMatching().get(sequence);
                // 普通或批次当前持有的结算事件，direct 且 ready 才可取事实。
                var event = active.settlementEvent();
                if (event == null && active.orderBatch != null)
                    event = active.orderBatch.itemSettlementEvent != null
                            ? active.orderBatch.itemSettlementEvent : active.orderBatch.settlementEvent;
                if (event != null && event.direct() && event.ready()) result = event.directResult();
            }
            if (result != null) return result;
            // 距当前撮合等待截止点的剩余纳秒数。
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) break;
            if (idle++ < 1_024) {
                Thread.onSpinWait();
            } else {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        state, Math.min(remainingNanos, 1_000L));
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("matching completion wait was interrupted");
                }
            }
        }
        return null;
    }
}
