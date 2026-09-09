package com.surprising.aeron.service.execution;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.state.model.CoreOrderState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** 跨 matcher 分片的清算撤单：按既定订单顺序提交、轮询，首个失败后停止后续原生撤单。 */
final class CrossShardCancellationCoordinator {
    /** 唯一写入协调进度和最终提交结果的交易 owner。 */
    private final TradingCoreRuntime owner;
    /** 只保存尚未完成的跨分片命令，生命周期受 pendingMatching 的有界容量限制。 */
    private final ArrayDeque<Progress> pending = new ArrayDeque<>();

    CrossShardCancellationCoordinator(TradingCoreRuntime owner) { this.owner = owner; }

    void start(PendingMatching command, List<CoreOrderState> orders) {
        if (command.crossShardCancellationStarted) return;
        command.crossShardCancellationStarted = true;
        pending.addLast(new Progress(command, orders));
        poll();
    }

    void poll() {
        for (int work = 0; work < 64 && !pending.isEmpty(); work++) {
            Progress progress = pending.peekFirst();
            if (progress.token != 0) {
                var result = (CoreMatchingResult) owner.matcherPipeline.pollControl(progress.shard, progress.token);
                if (result == null) return;
                progress.results.add(result);
                progress.failed = !result.accepted();
                progress.token = 0;
                progress.index++;
            }
            if (progress.index < progress.orders.size()) {
                if (progress.failed) {
                    progress.results.add(new CoreMatchingResult(false, "NOT_SUBMITTED"));
                    progress.index++;
                    continue;
                }
                CoreOrderState order = progress.orders.get(progress.index);
                progress.shard = owner.matchingAdapter.matcherShardId(order.symbol());
                progress.token = owner.matcherPipeline.submitControl(progress.shard,
                        () -> owner.matchingAdapter.cancelForContinuation(order.userId(), order.orderId(), order.symbol()));
                continue;
            }
            var command = progress.command;
            var aggregate = owner.matchingAdapter.aggregateCancellationResults(progress.orders, progress.results);
            var evidenced = owner.matchingAdapter.executeControlWithEvidenceSync(
                    command.sequence(), command.command().header().commandId(), 0, 0,
                    command.command().header().submittedAtEpochMillis(), () -> aggregate);
            pending.removeFirst();
            command.crossShardCancellationStarted = false;
            owner.publishMatchingCompletion(command.sequence(), evidenced);
            command.matchingSubmitted();
            owner.matchingSubmissionCompleted(command);
        }
    }

    void clear() { pending.clear(); }

    /** 单条清算命令的跨分片进度；matcher 只执行当前订单，owner 收集不可变结果。 */
    private static final class Progress {
        /** 原始待提交命令及确定性撤单次序。 */
        final PendingMatching command;
        final List<CoreOrderState> orders;
        /** 与订单次序一一对应的原生结果，最终交由现有聚合器生成提交证据。 */
        final ArrayList<CoreMatchingResult> results;
        /** 当前订单下标、分片及尚未收集的控制 token。 */
        int index, shard;
        long token;
        /** 第一个失败后只补未提交标记，不再执行后续原生撤单。 */
        boolean failed;
        Progress(PendingMatching command, List<CoreOrderState> orders) {
            this.command = command; this.orders = orders;
            results = new ArrayList<>(orders.size());
        }
    }
}
