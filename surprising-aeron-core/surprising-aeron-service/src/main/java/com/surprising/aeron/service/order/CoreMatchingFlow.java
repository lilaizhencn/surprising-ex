package com.surprising.aeron.service.order;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.matcher.CoreMatchingResult;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.matcher.MatchingResult;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 交易步骤三：Owner 登记待命令、固定分片、收取准入并把固定输入交给 Matcher。普通交易使用命令槽已有的直达结算事件，强平/到期控制按实际订单路由先撤单；本类组织交接和结果读取，订单、余额、持仓与账本事实仍归原执行者。调用方遵守 Owner 与读取屏障约定。
 */
public final class CoreMatchingFlow {

    /** 组织本条交接链的所属交易运行时，不复制各线程业务事实。 */
    private final TradingCoreRuntime owner;

    /**
     * 保存非空所属运行时引用，不创建第二份交易状态。
     * @param owner 所属交易 Owner 运行时
     */
    public CoreMatchingFlow(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 若已有待命令，仍占新 Core 序号并登记有序拒绝项，先返回 MATCHING_PENDING，等待前序退休；队列为空时直接记录源水位和幂等拒绝结果后返回。拒单同样占应用序号。
     * @param message 当前完整 Core 命令
     * @param sourceKey 已确认的来源类型与实例键
     * @param fingerprint 当前原命令指纹
     * @param resultCode 需要有序登记的拒绝原因
     */
    CoreResponse recordRejectedMatching(CoreMessage message, TradingCoreRuntime.SourceKey sourceKey,
                                        CommandFingerprint fingerprint, CoreResultCode resultCode) {
        if (!owner.pendingMatching().isEmpty()) {
            // 本次准备或记录拒绝所占的 Core 应用序号。
            long sequence = owner.nextAppliedSequence();
            // 当前新建、查询或收据收集中的命令槽。
            CommandSlot pending = owner.admissions().newPendingMatching(sequence,
                    MatchingCommandAdmission.matchingOperation(message.header().messageType()),
                    message, fingerprint);
            putPendingMatching(pending);
            pending.rejectMatching(resultCode);
            owner.pendingMatching().completeSubmission(sequence);
            owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
            return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK,
                    CoreResultCode.MATCHING_PENDING, sequence,
                    TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        }
        // 本次准备或记录拒绝所占的 Core 应用序号。
        long sequence = owner.nextAppliedSequence();
        owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
        owner.resultLedger().storeOwnedResult(message.header().commandId(), fingerprint,
                ResponseStatus.REJECTED, resultCode, sequence,
                TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                sequence, TradingCoreRuntime.EMPTY_RESPONSE_DATA);
    }

    /**
     * 退休前解除生命周期范围，确认替换/下单准入已收齐，必要时取消已无后续执行的 Matcher 等待并释放收据；随后移队列、唤起同分片后继并刷新已提交水位。返回可复用原槽，异常不会回滚此前已解除的辅助记录。
     * @param sequence 当前命令的完整 Core 序号
     */
    public CommandSlot removePendingMatching(long sequence) {
        owner.admissions().pendingLifecycleScopes.remove(sequence);
        // 当前静态 Matcher 分片号。
        int shard = owner.pendingMatching().submissionShard(sequence);
        // 退休项是否恰好占着该分片提交头，决定是否唤起后继。
        boolean releasesSubmissionHead = shard >= 0
                && owner.pendingMatching().isSubmissionHead(sequence, shard);
        // 退休前仍在队列中的原槽，用于解除收据租约。
        CommandSlot current = owner.pendingMatching().get(sequence);
        if (current != null) {
            if (current.replacementAdmission() != null) {
                if (!current.replacementAdmission().complete())
                    throw new IllegalStateException("terminal command has incomplete replacement admission");
                owner.runtimeState().releaseReplacementAdmission(current.takeReplacementAdmission());
            }
            if (current.placeAdmission() != null && !current.placeAdmissionOwnerCollected()
                    && !collectPlaceAdmissionIfReady(current)) {
                throw new IllegalStateException("terminal command has an incomplete place admission");
            }
            current.committed();
            if (current.placeAdmission() != null && !current.placeAdmission().matcherConsumed()) {
                // 显式测试完成路径可直接提供终态事实；既已进入终态退休，该排队等待续步不再执行，可取消其收据等待。
                current.placeAdmission().cancelMatcherWait();
            }
            releasePlaceAdmissionIfConsumed(current);
            if (current.placeAdmission() != null) {
                throw new IllegalStateException("terminal command still owns an unconsumed place admission");
            }
        }
        // 已经从队列移除并清生命周期的同一槽对象。
        CommandSlot removed = owner.pendingMatching().remove(sequence);
        if (releasesSubmissionHead) owner.matchingProgress.submissionHeadReleased(shard);
        owner.refreshCommittedCoreSequence();
        return removed;
    }



    /**
     * 只检查序号仍在待队列且高于已提交水位，不在此递增或写入已提交水位。
     * @param sequence 当前命令的完整 Core 序号
     */
    public void commitMatchingSequence(long sequence) {
        if (!owner.pendingMatching().contains(sequence) || sequence <= owner.committedCoreSequence()) {
            throw new IllegalStateException("matching sequence is not pending above the committed watermark");
        }
    }

    /**
     * 固定命令的 Cluster 独立模式，可复用已准备分片，然后入队并为非批量命令登记分片提交顺序；登记运行异常时尝试移除或丢弃预备槽。容量前置检查在 try 外。
     * @param pending 本池仍在生命周期内的命令槽
     */
    void putPendingMatching(CommandSlot pending) {
        pending.clusterIndependent = owner.clusterPipelineAdmission();
        if (owner.pendingMatching().size() >= owner.pendingMatching().capacity()) {
            throw new IllegalStateException("matching pending capacity is exhausted");
        }
        try {
            if (owner.clusterPipelineAdmission()
                    && owner.preparedRouteUserLaneBit() != 0
                    && owner.preparedRouteMatcherShard() >= 0) {
                pending.cachedMatcherShard(owner.preparedRouteMatcherShard());
            }
            owner.pendingMatching().put(pending);
            // 当前完整消息类型，决定是否另登记分片提交链接。
            CoreMessageType type = pending.command().header().messageType();
            if (!TradingCoreRuntime.isOrderBatchCommand(type)) {
                owner.pendingMatching().registerSubmission(pending.sequence(), matcherShard(pending));
            }
        } catch (/* 当前登记、准入或哈希读取的运行异常，按所在方法清理或转为失败结果。 */ RuntimeException failure) {
            if (owner.pendingMatching().remove(pending.sequence()) == null) {
                owner.pendingMatching().discardPrepared(pending.sequence());
            }
            throw failure;
        }
    }

    /**
     * 优先返回槽缓存，否则从生命周期或普通命令路由取得品种并计算分片；空品种按既有规则使用零分片，随后缓存，不读取完整订单簿。
     * @param pending 本池仍在生命周期内的命令槽
     */
    public int matcherShard(CommandSlot pending) {
        // 此前已经计算的静态分片，负一表示未缓存。
        int cached = pending.cachedMatcherShard();
        if (cached >= 0) return cached;
        // 当前路由或旧单对应的永久品种字符串。
        String instrumentId = pending.operation() == CommandSlot.Operation.LIQUIDATION
                || pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
                || pending.operation() == CommandSlot.Operation.SETTLEMENT
                ? owner.admissions().pendingLifecycleSymbol(pending)
                : owner.admissions().matchingSymbol(pending.operation(), pending.decodedCommand());
        // 当前静态 Matcher 分片号。
        int shard = instrumentId == null || instrumentId.isBlank() ? 0 : owner.matchingAdapter().matcherShardId(instrumentId);
        pending.cachedMatcherShard(shard);
        return shard;
    }

    /**
     * 从活动订单派生索引按用户/品种/游标分页，再把仍有路由的订单转成控制撤单身份；使用原页的下一游标，路由缺失项跳过。
     * @param userId 活动订单选择用户，零用于选择品种全部用户
     * @param instrumentId 永久品种字符串
     * @param cursorOrderId 活动订单页的排他订单游标
     * @param maxOrders 本次最多选择的撤单订单数
     */
    public TradingCoreRuntime.LifecycleOrderChunk lifecycleOrders(
            long userId, String instrumentId, long cursorOrderId, int maxOrders) {
        // 从活动订单派生索引取得的原分页结果。
        var page = owner.activeOrderIndex().page(userId, instrumentId, cursorOrderId, maxOrders);
        // 本页仍有效的撤单路由身份列表。
        List<CancellationOrder> selected = new ArrayList<>(page.orderIds().size());
        for (Long /* 当前待查活动路由的订单号。 */ orderId : page.orderIds()) {
            // 当前活动订单索引保留的稳定路由身份。
            var route = owner.activeOrderIndex().activeOrderRoute(orderId);
            if (route != null) {
                selected.add(new CancellationOrder(route.orderId(), route.userId(), route.instrumentId()));
            }
        }
        return new TradingCoreRuntime.LifecycleOrderChunk(selected, page.nextCursorOrderId());
    }

    /**
     * 按强平批次动作顺序与账户校验收据挑出 PLANNED/ORDERED 且需要撤单的工作，使用剩余撤单预算分页，返回固定外层列表，不再执行账户强平。
     * @param pending 本池仍在生命周期内的命令槽
     */
    List<CancellationOrder> batchCancellationOrders(CommandSlot pending) {
        // 当前操作对应的已解码输入或准备的固定 Matcher 函数，具体类型见声明。
        var command = pending.decodedCommand().liquidationBatch();
        // 当前选中或准备交给 Matcher 的有序撤单身份。
        List<CancellationOrder> orders = new ArrayList<>();
        // 批次当前还可选择的撤单数量。
        int remaining = command.maxCancelOrders();
        for (/* 正在检查的强平动作下标。 */ int index = 0; index < command.actions().size(); index++) {
            if (remaining == 0) break;
            if (!pending.liquidationCheck().cancelsOrders(index)) continue;
            // 当前强平动作，含撤单分页游标。
            var action = command.actions().get(index);
            // 账户校验收据确认的当前强平计划状态。
            var liquidation = pending.liquidationCheck().liquidation(index);
            if (liquidation == null || (liquidation.status() != CoreLiquidationState.Status.PLANNED
                    && liquidation.status() != CoreLiquidationState.Status.ORDERED)) continue;
            // 当前用户/品种在剩余预算内的活动订单页。
            TradingCoreRuntime.LifecycleOrderChunk chunk = lifecycleOrders(
                    liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                    action.cursorOrderId(), remaining);
            orders.addAll(chunk.orders());
            remaining -= chunk.orders().size();
        }
        return List.copyOf(orders);
    }

    /**
     * 按命令准入时保存的独立模式临时进入异步作用域，防止延后恢复时丢失直达结算路径；提交后只退出本次自行进入的作用域。
     * @param pending 本池仍在生命周期内的命令槽
     */
    void submitMatching(CommandSlot pending) {
        // 命令可能在入口作用域退出后才获得分片提交头。
        // 使用槽中固定的独立准入模式，恢复异步作用域后继续走池化直达结算。
        // 本次是否为恢复独立准入模式而自行进入异步作用域，finally 只退出这一层。
        boolean enteredAsynchronousScope = pending.clusterIndependent
                && !owner.runtimeState().asynchronousCommands();
        if (enteredAsynchronousScope) owner.runtimeState().enterAsynchronousCommandScope();
        try {
            submitMatchingInCommandScope(pending);
        } finally {
            if (enteredAsynchronousScope) owner.runtimeState().exitAsynchronousCommandScope();
        }
    }

    /**
     * 等待本分片提交头及替换/强平校验，准备 Lane 结果目标；批量交既有批次执行器，多分片强平撤单必要时交跨分片协调。普通命令构造固定任务并可加准入等待门闩，再提交 Matcher；未就绪时直接返回，不在 Owner 阻塞等 Lane。
     * @param pending 本池仍在生命周期内的命令槽
     */
    private void submitMatchingInCommandScope(CommandSlot pending) {
        // 批次多项共用 Core 序号；有序提交已拥有后续项游标时，不再重进初次分片准入门闩。
        // 批次已开始提交且后续项继续使用同 Core 序号，允许绕过初次分片提交门闩。
        boolean batchContinuation = pending.orderBatch != null && pending.orderBatch.commitStarted()
                && pending.orderBatch.nextIndex > 0 && pending.isMatchingSubmitted();
        if (pending.crossShardCancellationStarted
                || !batchContinuation && owner.matchingProgress.submissionDeferred(pending.sequence())) return;
        if (!collectReplacementAdmissionIfReady(pending) || hasPendingMatchingRejection(pending.sequence())) return;
        if (!collectLiquidationChecksIfReady(pending) || pending.hasMatchingRejection()) return;
        pending.prepareLaneResultTarget(owner.responseArena());
        if (pending.placeAdmission() != null
                && (owner.runtimeState().isFusedExecution() || !owner.runtimeState().asynchronousCommands())) {
            if (!collectPlaceAdmissionIfReady(pending)
                    || hasPendingMatchingRejection(pending.sequence())) return;
        }
        if (pending.orderBatch != null) {
            owner.batches().submitOrderBatchMatching(pending);
            // 批次仍可能派发后续 Matcher 片段时，保留当前分片提交边界。
            if (pending.isMatchingSubmitted()
                    && owner.pendingMatching().submissionShard(pending.sequence()) >= 0
                    && owner.batches().remainingMatcherShardMask(pending.orderBatch) == 0)
                owner.matchingProgress.submissionCompleted(pending);
            return;
        }
        if (pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
                && owner.matchingAdapter().topology().matchingEngineCount() > 1) {
            // 当前选中或准备交给 Matcher 的有序撤单身份。
            List<CancellationOrder> orders = batchCancellationOrders(pending);
            // 列表单分片判定结果，负一为空、负二为混合分片。
            int shardId = singleMatcherShard(orders);
            if (shardId == -2) {
                owner.crossShardCancellations().start(pending, orders);
                return;
            }
            owner.matcherPipeline().submit(shardId < 0 ? matcherShard(pending) : shardId,
                    pending.sequence(), prepareMatchingCommand(pending, null));
            pending.matchingSubmitted();
            owner.matchingProgress.submissionCompleted(pending);
            return;
        }
        // 当前命令或批次保留的直达结算事件。
        var direct = owner.prepareForMatching(pending);
        // 当前操作对应的已解码输入或准备的固定 Matcher 函数，具体类型见声明。
        var command = prepareMatchingCommand(pending, direct);
        // 即将交给 Matcher 的固定任务，可再包槽内准入门闩。
        java.util.function.Supplier<?> matcherSubmission = command;
        if (direct != null && pending.placeAdmission() != null) {
            matcherSubmission = pending.gateAdmission(owner, pending.placeAdmission(), matcherShard(pending),
                    direct, matcherSubmission);
        }
        owner.matcherPipeline().submit(matcherShard(pending), pending.sequence(), matcherSubmission, direct);
        pending.matchingSubmitted();
        if (pending.placeAdmission() == null) owner.matchingProgress.submissionCompleted(pending);
    }

    /**
     * 空列表返回负一，全部同分片返回该号，遇到混合分片返回负二。
     * @param orders 已经按确定性顺序选好的撤单身份列表
     */
    int singleMatcherShard(List<CancellationOrder> orders) {
        // 列表单分片判定结果，负一为空、负二为混合分片。
        int shardId = -1;
        for (var /* 当前撤单路由或遍历的撤单身份，具体类型见声明。 */ order : orders) {
            // 当前撤单品种实际路由到的 Matcher 分片。
            int orderShard = owner.matchingAdapter().matcherShardId(order.instrumentId());
            if (shardId == -1) shardId = orderShard;
            else if (shardId != orderShard) return -2;
        }
        return shardId;
    }

    /**
     * 普通命令用缓存静态路由，批次按当前批次项的目标分片决定提交顺序。
     * @param pending 本池仍在生命周期内的命令槽
     */
    int pendingSubmissionShard(CommandSlot pending) {
        // 当前槽关联的可空批次上下文。
        OrderBatchPending batch = pending.orderBatch;
        return batch == null ? matcherShard(pending) : owner.batches().orderBatchMatcherShard(batch);
    }

    /**
     * 要求同步辅助结果非空且 Core 序号匹配，再保存到严格占用槽并增加调度修订，已有结果的保留规则由槽实现决定。
     * @param sequence 当前命令的完整 Core 序号
     * @param result 需要发布的同 Core 序号普通撮合回执
     */
    public void publishMatchingCompletion(long sequence, CoreMatchingResult result) {
        if (result == null || result.nativeCoreSequence() != sequence) {
            throw new IllegalStateException("synchronous matcher returned an invalid result");
        }
        owner.pendingMatching().required(sequence).publishMatchingCompletion(result);
        owner.pendingMatching().progressChanged();
    }

    /**
     * 故障或命令已不在队列时返回空；优先取已绑定事实，再取就绪直达事件，否则消费普通完成引用。直达 ready 仅表示可取撮合事实，不表示所有账户已结算完。
     * @param sequence 当前命令的完整 Core 序号
     */
    public MatchingResult takeMatchingResult(long sequence) {
        if (owner.fatalFailure() != null || !owner.pendingMatching().contains(sequence)) return null;
        // 当前新建、查询或收据收集中的命令槽。
        CommandSlot pending = owner.pendingMatching().get(sequence);
        if (pending == null) return null;
        if (pending.matchingResult() != null) return pending.matchingResult();
        // 当前命令或批次保留的直达结算事件。
        var direct = pending.settlementEvent();
        if (direct == null && pending.orderBatch != null) {
            direct = pending.orderBatch.itemSettlementEvent != null
                    ? pending.orderBatch.itemSettlementEvent : pending.orderBatch.settlementEvent;
        }
        if (direct != null && direct.direct()) return direct.ready() ? direct.directResult() : null;
        return pending.takeMatchingCompletion();
    }

    /**
     * 实际只检查批量下单准入事件存在且命令尚未标记 Matcher 提交，不代表普通 PLACE 收据状态。
     * @param pending 本池仍在生命周期内的命令槽
     */
    public boolean placeAdmissionOutstanding(CommandSlot pending) {
        // 当前槽关联的可空批次上下文。
        OrderBatchPending batch = pending.orderBatch;
        return batch != null && batch.placeBatchAdmissionEvent != null && !pending.isMatchingSubmitted();
    }

    /**
     * 融合模式先尝试内联准入，未完成检查 Lane 健康后返回假；完成后检查新身份，保存准入及先撤列表。已知业务/算术/参数错误转为槽内拒绝并释放提交头，其他运行异常继续传播。
     * @param pending 本池仍在生命周期内的命令槽
     */
    boolean collectReplacementAdmissionIfReady(CommandSlot pending) {
        // 当前替换 Lane 准入收据。
        var event = pending.replacementAdmission();
        if (event == null || pending.matchingAdmission() != null || pending.hasMatchingRejection()) return true;
        if (owner.runtimeState().isFusedExecution()) event.executeInlineIfReady();
        if (!event.complete()) { owner.runtimeState().assertAccountLanesHealthy(); return false; }
        // Lane 已报告的可空准入业务异常。
        RuntimeException rejection = event.rejection();
        try {
            if (rejection != null) throw rejection;
            // 已收齐的准入事件或其解析结果，具体阶段见声明。
            var admission = event.admission();
            if (admission.resolved().orderId() != admission.originalOrderId())
                owner.requireOrderIdentityAvailable(admission.userId(), admission.command());
            pending.withAdmission(admission);
            pending.withPreMatchingCancellations(pending.replacementAdmission().closeCapacityCancellations());
        } catch (/* 当前登记、准入或哈希读取的运行异常，按所在方法清理或转为失败结果。 */ com.surprising.aeron.exception.CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            pending.rejectMatching(failure instanceof com.surprising.aeron.exception.CoreStateRejectedException /* 带明确业务拒绝码的异常，用于映射 CoreResultCode。 */ rejected
                    ? CoreResultCode.fromRejectionCode(rejected.code())
                    : failure instanceof ArithmeticException ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
            owner.matchingProgress.submissionCompleted(pending);
        }
        return true;
    }

    /**
     * 仅强平单笔/批次才准备并轮询账户校验；未就绪返回假，业务拒绝保存结果并释放提交头，单笔还检查可执行状态，之后才允许撤原生订单。
     * @param pending 本池仍在生命周期内的命令槽
     */
    private boolean collectLiquidationChecksIfReady(CommandSlot pending) {
        if (pending.operation() != CommandSlot.Operation.LIQUIDATION
                && pending.operation() != CommandSlot.Operation.LIQUIDATION_BATCH) return true;
        // 本槽可复用的强平账户执行校验工作。
        var check = pending.liquidationCheck();
        if (!check.prepared()) {
            if (pending.operation() == CommandSlot.Operation.LIQUIDATION)
                check.prepare(pending.decodedCommand().liquidation(), owner.runtimeState(), owner.identities());
            else check.prepare(pending.decodedCommand().liquidationBatch(), owner.runtimeState(), owner.identities());
        }
        try { if (!check.poll()) return false; }
        catch (/* 本轮强平账户校验或执行资格检查的业务拒绝。 */ CoreStateRejectedException rejection) {
            pending.rejectMatching(CoreResultCode.fromRejectionCode(rejection.code()));
            owner.matchingProgress.submissionCompleted(pending);
            return true;
        }
        if (pending.operation() == CommandSlot.Operation.LIQUIDATION) {
            try { check.executable(0); }
            catch (/* 本轮强平账户校验或执行资格检查的业务拒绝。 */ CoreStateRejectedException rejection) {
                pending.rejectMatching(CoreResultCode.fromRejectionCode(rejection.code()));
                owner.matchingProgress.submissionCompleted(pending);
            }
        }
        return true;
    }

    /**
     * 融合模式可先内联执行；只在第一次 Owner 收据收集时保存准入或拒绝，随后推进分片提交顺序。已拒绝且未提交的等待者可取消；收据实际释放还需 Matcher 已消费。
     * @param pending 本池仍在生命周期内的命令槽
     */
    public boolean collectPlaceAdmissionIfReady(CommandSlot pending) {
        if (pending == null || pending.placeAdmission() == null) return true;
        // 已收齐的准入事件或其解析结果，具体阶段见声明。
        var admission = pending.placeAdmission();
        if (owner.runtimeState().isFusedExecution()) admission.executeInlineIfReady();
        if (!admission.complete()) return false;
        // Lane 已报告的可空准入业务异常。
        RuntimeException rejection = admission.rejection();
        if (!pending.placeAdmissionOwnerCollected()) {
            if (rejection != null) {
                // 按业务、算术或其他准入失败分类后的拒绝结果码。
                CoreResultCode resultCode = rejection instanceof CoreStateRejectedException /* 带明确业务拒绝码的异常，用于映射 CoreResultCode。 */ rejected
                        ? CoreResultCode.fromRejectionCode(rejected.code())
                        : rejection instanceof ArithmeticException ? CoreResultCode.ARITHMETIC_OVERFLOW
                        : CoreResultCode.INVALID_COMMAND;
                pending.rejectMatching(resultCode);
            } else {
                // Lane 完成准入后收集的普通下单输入。
                ResolvedPlaceOrder admitted = owner.runtimeState().collectPlaceAdmission(admission);
                if (owner.realtimeCapture() != null) pending.realtimeResolvedTakerOrder(admitted);
            }
            pending.markPlaceAdmissionOwnerCollected();
        }
        completePlaceAdmissionSubmission(pending);
        if (!pending.isMatchingSubmitted() && rejection != null && !admission.matcherConsumed()) {
            admission.cancelMatcherWait();
        }
        return true;
    }

    /**
     * 只有 Owner 已收齐且 Matcher 已消费该收据时，取走并归还下单准入事件。
     * @param pending 本池仍在生命周期内的命令槽
     */
    void releasePlaceAdmissionIfConsumed(CommandSlot pending) {
        if (pending != null && pending.placeAdmissionOwnerCollected()
                && pending.placeAdmission() != null && pending.placeAdmission().matcherConsumed()) {
            owner.runtimeState().releasePlaceAdmission(pending.takePlaceAdmission());
        }
    }

    /**
     * 命令已标提交且仍占分片提交链接时，通知推进并释放该链接，不代表命令终态。
     * @param pending 本池仍在生命周期内的命令槽
     */
    private void completePlaceAdmissionSubmission(CommandSlot pending) {
        if (pending != null && pending.isMatchingSubmitted()
                && owner.pendingMatching().submissionShard(pending.sequence()) >= 0) {
            owner.matchingProgress.submissionCompleted(pending);
        }
    }

    /**
     * 检查槽池仍占用完整序号且已登记拒绝码，不要求该槽此刻一定已链接到全局队列。
     * @param sequence 当前命令的完整 Core 序号
     */
    public boolean hasPendingMatchingRejection(long sequence) {
        return owner.pendingMatching().claimed(sequence)
                && owner.pendingMatching().required(sequence).hasMatchingRejection();
    }

    /**
     * 先要求 Owner 身份，再在各 Matcher 提交栅栏读取订单簿哈希；单分片直接读，多分片各自读后聚合。后续运行异常包装失败 Future，Owner 身份检查异常直接抛出。
     */
    public CompletableFuture<Integer> matchingStateHashAsync() {
        owner.assertOwner();
        try {
            return owner.matchingAdapter().topology().matchingEngineCount() == 1
                    ? owner.matcherPipeline().readAtSubmissionFence(0,
                    () -> owner.matchingAdapter().orderBooksStateHashAsync().join())
                    : owner.matcherPipeline().readEachAsync(owner.matchingAdapter()::stateHashShard)
                    .thenApply(owner.matchingAdapter()::aggregateBookHash);
        } catch (/* 当前登记、准入或哈希读取的运行异常，按所在方法清理或转为失败结果。 */ RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * 控制命令准备有序撤单任务；普通命令要求已有直达事件，再按操作取固定输入、活动索引路由或替换准入，返回槽内固定任务。下单自己的先撤由准入门闩处理，避免再放共同列表执行。
     * @param pending 本池仍在生命周期内的命令槽
     * @param direct 命令槽已经拥有的直达结算事件，控制路径可为空
     */
    Supplier<?> prepareMatchingCommand(CommandSlot pending,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent direct) {
        if (isControlCommand(pending)) return prepareControlCancellations(pending);
        java.util.Objects.requireNonNull(direct, "trading command requires its direct settlement slot");
        pending.matcherPreCancellations(pending.operation() == CommandSlot.Operation.PLACE
                && pending.placeAdmission() != null ? List.of() : preMatchingCancellationOrders(pending));
        // 当前命令头的请求用户编号。
        long userId = pending.command().header().userId();
        // 当前静态 Matcher 分片号。
        int shard = matcherShard(pending);
        return switch (pending.operation()) {
            case PLACE -> pending.preparePlaceMatching(owner, shard, userId,
                    java.util.Objects.requireNonNull(pending.placeAdmission(), "place admission receipt").matchingOrder(),
                    null, direct);
            case CANCEL -> {
                // 当前操作对应的已解码输入或准备的固定 Matcher 函数，具体类型见声明。
                var command = pending.decodedCommand().cancelOrder();
                // 当前活动订单索引保留的稳定路由身份。
                var route = java.util.Objects.requireNonNull(owner.activeOrderIndex().activeOrderRoute(command.orderId()),
                        "admitted cancellation route");
                yield pending.prepareCancelMatching(owner, shard, command.orderId(), userId, route.instrumentId(), direct);
            }
            case REPLACE, AMEND -> {
                // 已收齐的准入事件或其解析结果，具体阶段见声明。
                var admission = owner.requireMatchingAdmission(pending);
                yield pending.prepareReplaceMatching(owner, shard, admission.resolved().orderId(), userId,
                        admission.originalOrderId(), admission.command().instrumentId(), admission.resolved(), direct);
            }
            case TRIGGER -> pending.preparePlaceMatching(owner, shard, userId, null,
                    java.util.Objects.requireNonNull(pending.admittedMatchingOrder(), "trigger admission receipt"), direct);
            default -> throw new IllegalStateException("unsupported trading operation " + pending.operation());
        };
    }

    /**
     * 只把单笔强平、批次强平和到期结算归为控制撮合，不包括普通下单/撤单/改单/触发子单。
     * @param pending 本池仍在生命周期内的命令槽
     */
    private boolean isControlCommand(CommandSlot pending) {
        return pending.operation() == CommandSlot.Operation.LIQUIDATION
                || pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
                || pending.operation() == CommandSlot.Operation.SETTLEMENT;
    }

    /**
     * 按强平校验、批次预算或到期进度选出当前需撤订单，再把已确定前置撤单放在最前面，准备槽内控制任务；本方法本身不操作订单簿。
     * @param pending 本池仍在生命周期内的命令槽
     */
    private Supplier<?> prepareControlCancellations(CommandSlot pending) {
        // 当前选中或准备交给 Matcher 的有序撤单身份。
        List<CancellationOrder> orders;
        switch (pending.operation()) {
            case LIQUIDATION -> {
                // 当前操作对应的已解码输入或准备的固定 Matcher 函数，具体类型见声明。
                var command = pending.decodedCommand().liquidation();
                // 账户校验收据确认的当前强平计划状态。
                var liquidation = pending.liquidationCheck().liquidation(0);
                orders = liquidation == null || !pending.liquidationCheck().executable(0) ? List.of()
                        : lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                                command.cursorOrderId(), command.maxOrders()).orders();
            }
            case LIQUIDATION_BATCH -> orders = batchCancellationOrders(pending);
            case SETTLEMENT -> {
                // 当前操作对应的已解码输入或准备的固定 Matcher 函数，具体类型见声明。
                var command = pending.decodedCommand().settlement();
                // 该品种当前到期结算进度，已完成撤单阶段时不再选挂单。
                var progress = owner.runtimeLifecycleProgress(command.instrumentId());
                orders = progress != null && progress.ordersComplete() ? List.of()
                        : lifecycleOrders(0, command.instrumentId(),
                                command.cursorOrderId(), command.maxOrders()).orders();
            }
            default -> throw new IllegalStateException("unsupported cancellation control");
        }
        // 已准入确定要先执行的撤单路由列表。
        var prefix = preMatchingCancellationOrders(pending);
        if (!prefix.isEmpty()) {
            // 按前置撤单在前、当前控制撤单在后拼接的临时列表。
            var combined = new ArrayList<CancellationOrder>(prefix.size() + orders.size());
            combined.addAll(prefix); combined.addAll(orders); orders = combined;
        }
        return pending.prepareControlMatching(owner, orders);
    }

    /**
     * 把先撤订单号按当前活动索引转成路由身份，已经没有路由的订单跳过，空结果共用空表。
     * @param pending 本池仍在生命周期内的命令槽
     */
    List<CancellationOrder> preMatchingCancellationOrders(CommandSlot pending) {
        // 准入保存的原先撤订单号列表。
        List<Long> orderIds = pending.preMatchingCancellationOrderIds();
        if (orderIds.isEmpty()) return List.of();
        // 当前选中或准备交给 Matcher 的有序撤单身份。
        ArrayList<CancellationOrder> orders = new ArrayList<>(orderIds.size());
        for (long /* 当前待查活动路由的订单号。 */ orderId : orderIds) {
            // 当前撤单路由或遍历的撤单身份，具体类型见声明。
            var order = owner.activeOrderIndex().activeOrderRoute(orderId);
            if (order != null) {
                orders.add(new CancellationOrder(order.orderId(), order.userId(), order.instrumentId()));
            }
        }
        return orders.isEmpty() ? List.of() : orders;
    }
}
