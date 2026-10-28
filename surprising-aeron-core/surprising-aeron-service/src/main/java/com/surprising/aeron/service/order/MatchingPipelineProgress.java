package com.surprising.aeron.service.order;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.order.admission.PlaceBatchAdmissionEvent;
import com.surprising.aeron.service.order.command.OrderBatchKind;

/**
 * 交易步骤三的 Owner 调度续接：消耗已有准入/撮合/结算通知，推动可提交分片和延后批次，给有序提交队首提供进展提示。就绪位是提示而非事实副本，权威进度仍在命令槽、准入事件和结算事件中；无通知时必要的 Owner 本地续步也需继续推进。
 */
public final class MatchingPipelineProgress {
    /** 当前 Owner 运行时及其现有队列/事件入口。 */
    private final TradingCoreRuntime owner;
    /** 哪些 Matcher 分片可重试提交头的合并提示位，不复制实际准入结果。 */
    private long readyShardMask;
    /** 下次空通知路径健康检查的 nanoTime 时间点。 */
    private long nextHealthCheckNs = System.nanoTime();

    /**
     * 保存非空所属运行时，使用既有命令和事件结构推进，不创建新工作线程。
     * @param owner 所属交易 Owner 运行时
     */
    public MatchingPipelineProgress(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 合法非负分片释放提交头后置就绪位，外层保证分片在 long 位范围内。
     * @param shard 待重试的非负全局分片号
     */
    void submissionHeadReleased(int shard) {
        if (shard >= 0) readyShardMask |= 1L << shard;
    }

    /**
     * 检查各分片是否仍有提交头，有则置位，提示批量结束后重试后继命令。
     */
    void batchCompleted() {
        for (/* 当前扫描或推进的全局分片号。 */ int shard = 0; shard < owner.matchingAdapter().topology().matchingEngineCount(); shard++) {
            if (owner.pendingMatching().submissionHead(shard) != null) readyShardMask |= 1L << shard;
        }
    }

    /**
     * 不是本分片提交头则延后；还有更早未完成批次时，只有独立命令且它的分片不在前批次剩余项集合中才可越过该批次提交边界。
     * @param sequence 需要判断提交门闩的完整 Core 序号
     */
    boolean submissionDeferred(long sequence) {
        // 当前提交头、延后项或通知序号对应的命令槽。
        CommandSlot pending = owner.pendingMatching().get(sequence);
        if (pending != null) {
            // 该命令当前项真正需要的分片号。
            int shardId = owner.matchingFlow.pendingSubmissionShard(pending);
            if (!owner.pendingMatching().isSubmissionHead(sequence, shardId)) return true;
        }
        if (!owner.batches().hasPendingBatches() || sequence <= owner.batches().firstBatch().sequence)
            return false;
        // 未完成批次后续项仍可能访问其他分片，所以按剩余项分片集合拦住相关命令，其他独立分片可继续。
        return pending == null || !pending.clusterIndependent
                || (owner.batches().remainingMatcherShardMask(owner.batches().firstBatch())
                    & (1L << owner.matchingFlow.pendingSubmissionShard(pending))) != 0;
    }

    /**
     * 更新调度修订、解除当前分片提交链接，若有批次、后继或延后项则置位重试；只是提交门闩释放，不是业务终态。
     * @param pending 当前准备释放提交链接或检查拒绝的命令槽
     */
    public void submissionCompleted(CommandSlot pending) {
        owner.pendingMatching().progressChanged();
        // 当前扫描或推进的全局分片号。
        int shard = owner.matchingFlow.pendingSubmissionShard(pending);
        owner.pendingMatching().completeSubmission(pending.sequence());
        // 提交头释放后立即提示同分片后继，避免普通下单后的撤单或控制项还在等一个不会再来的 Lane 通知。
        if (pending.orderBatch != null || owner.pendingMatching().submissionHead(shard) != null
                || owner.pendingMatching().hasDeferred()) {
            readyShardMask |= 1L << shard;
        }
    }

    /**
     * 先收准入就绪分片提示，逐就绪分片只推进提交头。批量准入完成后保存准入并提交；失败则清理准备数据，单项保留已知拒绝，多项转顺序准入。普通 PLACE 可以先提交 Matcher 等收据，顺序批次项仍由批次执行器拥有游标。
     */
    public void progressPlaceBatchAdmissions() {
        drainPlaceAdmissionNotifications();
        if (readyShardMask == 0 && hasDeferredMatchingSubmission()) {
            // 当前全局队首，用于补充可推进分片或判断本地续步。
            CommandSlot head = owner.pendingMatching().get(owner.pendingMatching().firstSequence());
            readyShardMask |= 1L << owner.matchingFlow.pendingSubmissionShard(head);
        }
        // 本轮开始时的就绪位副本，逐位选出要推进的分片。
        long readyShards = readyShardMask;
        while (readyShards != 0) {
            // 当前扫描或推进的全局分片号。
            int shard = Long.numberOfTrailingZeros(readyShards);
            // 当前分片的单个位值，用于本地提示保留或清除。
            long shardBit = 1L << shard;
            readyShards &= ~shardBit;
            while ((readyShardMask & shardBit) != 0) {
                // 当前提交头、延后项或通知序号对应的命令槽。
                CommandSlot pending = owner.pendingMatching().submissionHead(shard);
                if (pending == null) {
                    readyShardMask &= ~shardBit;
                    break;
                }
                // 普通 PLACE 的可空 Lane 准入事件。
                var admission = pending.placeAdmission();
                // 当前提交头的可空批次上下文。
                OrderBatchPending orderBatch = pending.orderBatch;
                // 当前批次共用的可空流水准入事件。
                PlaceBatchAdmissionEvent batchAdmission = orderBatch == null
                        ? null : orderBatch.placeBatchAdmissionEvent;
                if (pending != null && pending.hasMatchingRejection()) {
                    // 同步准入可能在登记分片提交头之前就拒绝；其拒绝由有序提交消费，不再给它重建撮合订单。
                    readyShardMask &= ~shardBit;
                    break;
                }
                if (admission == null && batchAdmission == null) {
                    if (orderBatch != null && pending.clusterIndependent
                            && orderBatch.kind == OrderBatchKind.CANCEL && orderBatch.activated()
                            && !pending.isMatchingSubmitted()) {
                        owner.matchingFlow.submitMatching(pending);
                    }
                    // 控制或普通项可因前一个提交头而延后；顺序批次自己的项游标仍归批次执行器，不能在这里重复提交。
                    if (orderBatch == null && !pending.isMatchingSubmitted() && !pending.deferredMatching()) {
                        owner.matchingFlow.submitMatching(pending);
                        if (pending.isMatchingSubmitted()) continue;
                    }
                    readyShardMask &= ~shardBit;
                    break;
                }
                if (batchAdmission != null) {
                    if (!batchAdmission.complete()) {
                        // 就绪位只是合并提示，事件未完成时保留，避免同分片较晚通知合并后被清掉。
                        break;
                    }
                    // 批量准入收据报告的运行异常。
                    RuntimeException rejection = batchAdmission.rejection();
                    if (rejection != null) {
                        owner.runtimeState().discardPlaceBatchAdmission(batchAdmission);
                        owner.runtimeState().releasePlaceBatchAdmission(batchAdmission);
                        orderBatch.placeBatchAdmissionEvent = null;
                        owner.batches().unregisterPipelinedBatchSymbols(orderBatch);
                        orderBatch.clearPreparedDecisionCache();
                        orderBatch.pipelined = false;
                        orderBatch.sequentialAdmission = true;
                        // 只有一项时，Lane 已校验并回滚，无需重做部分批次准入；有序提交直接保留该已知拒绝。
                        if (orderBatch.items.size() == 1
                                && (rejection instanceof CoreStateRejectedException
                                || rejection instanceof ArithmeticException
                                || rejection instanceof IllegalArgumentException)) {
                            // 已知单项准入失败对应的业务结果码。
                            CoreResultCode code = rejection instanceof CoreStateRejectedException /* 含业务拒绝编码的异常。 */ stateRejection
                                    ? CoreResultCode.fromRejectionCode(stateRejection.code())
                                    : rejection instanceof ArithmeticException
                                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND;
                            owner.batches().appendOrderBatchResult(orderBatch.items.getFirst(),
                                    ResponseStatus.REJECTED, code);
                            orderBatch.nextIndex = 1;
                        }
                        readyShardMask &= ~shardBit;
                        if (orderBatch.commitStarted()) {
                            owner.restoreMatchingCommitContext(pending);
                            try {
                                owner.batches().startOrderBatchItem(orderBatch, pending,
                                        orderBatch.clusterTimestamp, orderBatch.clusterPosition, true);
                            } finally {
                                owner.clearFactContext();
                            }
                        } else {
                            orderBatch.activated(false);
                            submitDeferredMatchingAfterBatch();
                        }
                        break;
                    }
                    if (!orderBatch.admissionCollected()) {
                        owner.runtimeState().registerPlaceBatchAdmission(batchAdmission);
                        orderBatch.admissionCollected(true);
                    }
                    if (!pending.isMatchingSubmitted()) {
                        owner.batches().submitPipelinedPlaceBatch(pending, orderBatch);
                        pending.matchingSubmitted();
                        if (owner.batches().remainingMatcherShardMask(orderBatch) == 0)
                            submissionCompleted(pending);
                    }
                    if (owner.pendingMatching().isSubmissionHead(pending.sequence(), shard)) {
                        // 后续批次项仍占该分片提交边界，Matcher/Lane 运行期间不在这里反复探查已完成准入。
                        readyShardMask &= ~shardBit;
                        break;
                    }
                    continue;
                }
                // 普通 PLACE 获得提交头后可先进入 Matcher 等 Lane 收据；这里推进的是提交门闩，不是轮询准入业务。
                if (!pending.isMatchingSubmitted()) {
                    owner.matchingFlow.submitMatching(pending);
                    if (pending.isMatchingSubmitted()) continue;
                }
                readyShardMask &= ~shardBit;
                break;
            }
        }
    }

    /**
     * 取清运行时账户准入的分片就绪位并合入本地提示，不在这里取走准入 payload。
     */
    void drainPlaceAdmissionNotifications() {
        readyShardMask |= owner.runtimeState().takeAccountAdmissionReadyShardMask();
    }

    /**
     * 比较最早延后命令和未激活批次的 Core 序号，先推进较早项；流水批次可尝试并行激活，顺序准入必须等到全局队首，使用原时间、位置和来源恢复延后命令。
     */
    public void submitDeferredMatchingAfterBatch() {
        while (!owner.pendingMatching().isEmpty()) {
            // 批次链与延后项都按递增 Core 序号登记，只比较各自最早项再推进。
            // 最早批次或当前队首关联的批次。
            OrderBatchPending batch = owner.batches().firstBatch();
            // 全局顺序中最早的延后撮合项。
            CommandSlot deferredPending = owner.pendingMatching().firstDeferred();
            // 该延后项的可空 Core 序号，供与批次头比较。
            Long deferredSequence = deferredPending == null ? null : deferredPending.sequence();
            // 当前提交头、延后项或通知序号对应的命令槽。
            CommandSlot pending;
            if (deferredSequence != null && (batch == null || deferredSequence < batch.sequence)) {
                pending = owner.pendingMatching().get(deferredSequence);
                batch = null;
            } else if (batch != null && !batch.activated()) {
                pending = owner.pendingMatching().get(batch.sequence);
            } else {
                return;
            }
            if (batch != null && !batch.activated()) {
                if (owner.batches().tryActivatePipelinedOrderBatch(batch, pending)) continue;
                // 顺序准入共用当前回滚/提交边界，前序命令尚能发布该边界时不能启动。
                if (owner.pendingMatching().firstSequence() != batch.sequence) return;
                owner.batches().activateOrderBatch(batch, pending, true);
                return;
            }
            if (pending != null && pending.deferredMatching()) {
                // 恢复延后准入后得到的可空响应，未就绪时停止本轮。
                CoreResponse response = owner.admissions().prepareMatching(pending.command(), pending.deferredClusterTimestamp(),
                        pending.deferredClusterPosition(), pending.deferredSourceKey(), pending.operation(),
                        pending.fingerprint(), pending);
                if (response == null) return;
                if (response.status() == ResponseStatus.REJECTED) continue;
                continue;
            }
        }
    }

    /**
     * 检查跨分片撤单、分片就绪位、运行时/Matcher 通知或 Owner 本地续步是否仍需推进，不代表命令已经完成。
     */
    public boolean hasDrainWork() {
        return owner.crossShardCancellations().hasPending()
                || readyShardMask != 0
                || owner.runtimeState().hasMatchingNotifications()
                || owner.matcherPipeline().hasMatchingCompletions()
                || hasLocalMatchingWork();
    }

    /**
     * 各推进一次跨分片控制、下单收据、分片提交及 Matcher/结算通知，更新待队列修订；终态账户收取和提交仍由有序提交器完成。
     */
    public void drainMatchingCompletions() {
        if (owner.crossShardCancellations().hasPending()) owner.crossShardCancellations().poll();
        drainPlaceAdmissionCompletions();
        if (readyShardMask != 0 || owner.runtimeState().hasPlaceAdmissionNotifications()
                || hasDeferredMatchingSubmission()) {
            progressPlaceBatchAdmissions();
        }
        if (owner.matcherPipeline().hasMatchingCompletions()) {
            owner.matcherPipeline().drainMatchingCompletions();
            owner.pendingMatching().progressChanged();
        }
        if (owner.runtimeState().hasSettlementNotifications()) owner.commits.drainMatcherSettlementCompletions();
    }

    /**
     * 按就绪 Lane 消费通知序号；未提交项只唤起其分片，已提交项尝试由 Owner 收据收集并记进展，准入数据保留在原事件。
     */
    private void drainPlaceAdmissionCompletions() {
        // 已取清的普通下单准入就绪 Lane 位集合。
        long readyLanes = owner.runtimeState().takePlaceAdmissionReadyLaneMask();
        while (readyLanes != 0) {
            // 当前正在消费准入通知的 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(readyLanes);
            readyLanes &= readyLanes - 1;
            // 当前 Lane 通知队列取出的 Core 序号，零表示该队列暂空。
            long sequence;
            while ((sequence = owner.runtimeState().pollPlaceAdmissionReady(laneId)) != 0) {
                // 当前提交头、延后项或通知序号对应的命令槽。
                CommandSlot pending = owner.pendingMatching().get(sequence);
                if (pending == null || pending.placeAdmission() == null) continue;
                if (!pending.isMatchingSubmitted()) {
                    readyShardMask |= 1L << owner.matchingFlow.pendingSubmissionShard(pending);
                } else if (owner.matchingFlow.collectPlaceAdmissionIfReady(pending)) {
                    owner.pendingMatching().progressChanged();
                }
            }
        }
    }

    /**
     * 判断没有新通知时是否仍有资金派发、批次激活/提交或可就绪队首等本地工作；其中 busy-spin 选择是调度策略，实际完成仍由事件位判断。
     */
    public boolean hasLocalMatchingWork() {
        if (readyShardMask != 0 || owner.commits.hasFinancialDispatchWork()) return true;
        // 当前全局队首，用于补充可推进分片或判断本地续步。
        CommandSlot head = owner.pendingMatching().get(owner.pendingMatching().firstSequence());
        // 最早批次或当前队首关联的批次。
        OrderBatchPending batch = head == null ? null : head.orderBatch;
        // 交接和最终元数据提交可能没有新的结算通知，仍要推进这些有界 Owner 续步。
        // 独占 CPU 的吞吐配置选择持续探查，完成条件仍由事件位和实际 Lane 提交状态决定。
        return batch != null && (!batch.activated() || batch.laneCommitEvent != null || batch.itemAdmission != null)
                || head != null && !head.isMatchingSubmitted() && head.placeAdmission() == null
                && !head.deferredMatching()
                || head != null && owner.commits.matchingCommitReady(head);
    }

    /**
     * 只检查全局首项是否为无拒绝、未提交、未标延后且非批次的普通待提交命令。
     */
    private boolean hasDeferredMatchingSubmission() {
        // 当前全局队首，用于补充可推进分片或判断本地续步。
        CommandSlot head = owner.pendingMatching().get(owner.pendingMatching().firstSequence());
        return head != null && !head.hasMatchingRejection()
                && !head.isMatchingSubmitted() && !head.deferredMatching()
                && head.orderBatch == null;
    }

    /**
     * 用当前 nanoTime 检查通知及周期性健康状态。
     */
    public boolean hasNotifications() {
        return hasNotifications(System.nanoTime());
    }

    /**
     * 空路径最多约每毫秒主动检查一次 Owner 健康，避免故障 Lane 没发通知而永远不被发现；再探查运行时和 Matcher 已有通知。
     * @param now 当前用于健康检查节流的 nanoTime 值
     */
    public boolean hasNotifications(long now) {
        // 故障 Lane 可能没有完成通知，空路径仍按节流间隔查健康；实际 apply/commit 各自仍检查。
        if (now - nextHealthCheckNs >= 0) {
            owner.assertHealthy();
            nextHealthCheckNs = now + 1_000_000L;
        }
        return owner.runtimeState().hasMatchingNotifications() || owner.matcherPipeline().hasMatchingCompletions();
    }
}
