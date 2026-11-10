package com.surprising.aeron.service.order;

import com.surprising.aeron.service.liquidation.LiquidationService;
import com.surprising.aeron.service.settlement.ContractExpirySettlementService;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder;
import com.surprising.aeron.service.matcher.OrderMatchingResult;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class OrderMatchingCoordinator {

    /** 组织本条交接链的所属交易运行时，不复制各线程业务事实。 */
    private final TradingCoreRuntime owner;

    /**
     * 保存非空所属运行时引用，不创建第二份交易状态。
     * @param owner 所属交易 Owner 运行时
     */
    public OrderMatchingCoordinator(TradingCoreRuntime owner) {
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
            OrderCommand pending = owner.admissions().newPendingMatching(sequence,
                    OrderAdmissionService.matchingOperation(message.header().messageType()),
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
        owner.resultLedger().storeOwnedResult(message.header().commandIdMsb(), message.header().commandIdLsb(), fingerprint,
                ResponseStatus.REJECTED, resultCode, sequence,
                TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                sequence, TradingCoreRuntime.EMPTY_RESPONSE_DATA);
    }

    /**
     * 退休前解除生命周期范围，确认替换/下单准入已收齐，必要时取消已无后续执行的 Matcher 等待并释放收据；随后移队列、唤起同分片后继并刷新已提交水位。返回可复用原槽，异常不会回滚此前已解除的辅助记录。
     * @param sequence 当前命令的完整 Core 序号
     */
    public OrderCommand removePendingMatching(long sequence) {
        owner.admissions().releasePendingLifecycle(sequence);
        // 当前静态 Matcher 分片号。
        int shard = owner.pendingMatching().submissionShard(sequence);
        // 退休项是否恰好占着该分片提交头，决定是否唤起后继。
        boolean releasesSubmissionHead = shard >= 0
                && owner.pendingMatching().isSubmissionHead(sequence, shard);
        // 退休前仍在队列中的原槽，用于解除收据租约。
        OrderCommand current = owner.pendingMatching().get(sequence);
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
        OrderCommand removed = owner.pendingMatching().remove(sequence);
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
    void putPendingMatching(OrderCommand pending) {
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
    public int matcherShard(OrderCommand pending) {
        // 此前已经计算的静态分片，负一表示未缓存。
        int cached = pending.cachedMatcherShard();
        if (cached >= 0) return cached;
        // 当前路由或旧单对应的永久品种字符串。
        String instrumentId = pending.operation() == OrderCommand.Operation.LIQUIDATION
                || pending.operation() == OrderCommand.Operation.LIQUIDATION_BATCH
                || pending.operation() == OrderCommand.Operation.SETTLEMENT
                ? owner.admissions().pendingLifecycleSymbol(pending)
                : owner.admissions().matchingSymbol(pending.operation(), pending.decodedCommand());
        // 当前静态 Matcher 分片号。
        int shard = instrumentId == null || instrumentId.isBlank() ? 0 : owner.matchingAdapter().matcherShardId(instrumentId);
        pending.cachedMatcherShard(shard);
        return shard;
    }

    /**
     * 按强平批次动作顺序与账户校验收据挑出 PLANNED/ORDERED 且需要撤单的工作，使用剩余撤单预算分页，返回固定外层列表，不再执行账户强平。
     * @param pending 本池仍在生命周期内的命令槽
     */
    List<CancellationOrder> batchCancellationOrders(OrderCommand pending) {
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
            ActiveOrderIndex.LifecycleOrderChunk chunk = owner.activeOrderIndex().lifecycleOrders(
                    liquidation.userId(), owner.identities().instrumentId(liquidation.symbolId()),
                    action.cursorOrderId(), remaining);
            orders.addAll(chunk.orders());
            remaining -= chunk.orders().size();
        }
        // 本方法独占新建的列表；后续仅由当前命令槽借给 Matcher 读取。
        return orders;
    }

    /**
     * 等待本分片提交头及替换/强平校验，准备 Lane 结果目标；批量交既有批次执行器，多分片强平撤单必要时交跨分片协调。普通命令构造固定任务并可加准入等待门闩，再提交 Matcher；未就绪时直接返回，不在 Owner 阻塞等 Lane。
     * @param pending 本池仍在生命周期内的命令槽
     */
    void submitMatching(OrderCommand pending) {
        // 批次多项共用 Core 序号；有序提交已拥有后续项游标时，不再重进初次分片准入门闩。
        // 批次已开始提交且后续项继续使用同 Core 序号，允许绕过初次分片提交门闩。
        boolean batchContinuation = pending.orderBatch != null && pending.orderBatch.commitStarted()
                && pending.orderBatch.nextIndex > 0 && pending.isMatchingSubmitted();
        if (pending.crossShardCancellationStarted
                || !batchContinuation && owner.matchingProgress.submissionDeferred(pending.sequence())) return;
        if (!collectReplacementAdmissionIfReady(pending) || hasPendingMatchingRejection(pending.sequence())) return;
        if (!collectLiquidationChecksIfReady(pending) || pending.hasMatchingRejection()) return;
        pending.prepareLaneResultTarget(owner.responseArena());
        if (pending.orderBatch != null) {
            owner.batches().submitOrderBatchMatching(pending);
            // 批次仍可能派发后续 Matcher 片段时，保留当前分片提交边界。
            if (pending.isMatchingSubmitted()
                    && owner.pendingMatching().submissionShard(pending.sequence()) >= 0
                    && owner.batches().remainingMatcherShardMask(pending.orderBatch) == 0)
                owner.matchingProgress.submissionCompleted(pending);
            return;
        }
        if (pending.operation() == OrderCommand.Operation.LIQUIDATION_BATCH
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
                    pending.sequence(), prepareControlCancellations(pending, orders), null);
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
            int orderShard = owner.matchingAdapter().topology().matcherShardId(order.symbolId());
            if (shardId == -1) shardId = orderShard;
            else if (shardId != orderShard) return -2;
        }
        return shardId;
    }

    /**
     * 普通命令用缓存静态路由，批次按当前批次项的目标分片决定提交顺序。
     * @param pending 本池仍在生命周期内的命令槽
     */
    int pendingSubmissionShard(OrderCommand pending) {
        // 当前槽关联的可空批次上下文。
        OrderBatchProcessor.PendingBatch batch = pending.orderBatch;
        return batch == null ? matcherShard(pending) : owner.batches().orderBatchMatcherShard(batch);
    }

    /**
     * 要求同步辅助结果非空且 Core 序号匹配，再保存到严格占用槽并增加调度修订，已有结果的保留规则由槽实现决定。
     * @param sequence 当前命令的完整 Core 序号
     * @param result 需要发布的同 Core 序号普通撮合回执
     */
    public void publishMatchingCompletion(long sequence, OrderMatchingResult.Control result) {
        if (result == null || result.nativeCoreSequence() != sequence) {
            throw new IllegalStateException("synchronous matcher returned an invalid result");
        }
        owner.pendingMatching().required(sequence).publishMatchingCompletion(result);
        owner.pendingMatching().progressChanged();
    }

    /**
     * 准入未完成检查 Lane 健康后返回假；完成后检查新身份，保存准入及先撤列表。已知业务/算术/参数错误转为槽内拒绝并释放提交头，其他运行异常继续传播。
     * @param pending 本池仍在生命周期内的命令槽
     */
    boolean collectReplacementAdmissionIfReady(OrderCommand pending) {
        // 当前替换 Lane 准入收据。
        var event = pending.replacementAdmission();
        if (event == null || pending.matchingAdmission() != null || pending.hasMatchingRejection()) return true;
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
    private boolean collectLiquidationChecksIfReady(OrderCommand pending) {
        if (pending.operation() != OrderCommand.Operation.LIQUIDATION
                && pending.operation() != OrderCommand.Operation.LIQUIDATION_BATCH) return true;
        // 本槽可复用的强平账户执行校验工作。
        var check = pending.liquidationCheck();
        if (!check.prepared()) {
            if (pending.operation() == OrderCommand.Operation.LIQUIDATION)
                check.prepare(pending.decodedCommand().liquidation(), owner.runtimeState(), owner.identities());
            else check.prepare(pending.decodedCommand().liquidationBatch(), owner.runtimeState(), owner.identities());
        }
        try { if (!check.poll()) return false; }
        catch (/* 本轮强平账户校验或执行资格检查的业务拒绝。 */ CoreStateRejectedException rejection) {
            pending.rejectMatching(CoreResultCode.fromRejectionCode(rejection.code()));
            owner.matchingProgress.submissionCompleted(pending);
            return true;
        }
        if (pending.operation() == OrderCommand.Operation.LIQUIDATION) {
            try { check.executable(0); }
            catch (/* 本轮强平账户校验或执行资格检查的业务拒绝。 */ CoreStateRejectedException rejection) {
                pending.rejectMatching(CoreResultCode.fromRejectionCode(rejection.code()));
                owner.matchingProgress.submissionCompleted(pending);
            }
        }
        return true;
    }

    /**
     * 只在第一次 Owner 收据收集时保存准入或拒绝，随后推进分片提交顺序。已拒绝且未提交的等待者可取消；收据实际释放还需 Matcher 已消费。
     * @param pending 本池仍在生命周期内的命令槽
     */
    public boolean collectPlaceAdmissionIfReady(OrderCommand pending) {
        if (pending == null || pending.placeAdmission() == null) return true;
        // 已收齐的准入事件或其解析结果，具体阶段见声明。
        var admission = pending.placeAdmission();
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
                OrderCommand.ResolvedPlaceOrder admitted = owner.runtimeState().collectPlaceAdmission(admission);
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
    void releasePlaceAdmissionIfConsumed(OrderCommand pending) {
        if (pending != null && pending.placeAdmissionOwnerCollected()
                && pending.placeAdmission() != null && pending.placeAdmission().matcherConsumed()) {
            owner.runtimeState().releasePlaceAdmission(pending.takePlaceAdmission());
        }
    }

    /**
     * 命令已标提交且仍占分片提交链接时，通知推进并释放该链接，不代表命令终态。
     * @param pending 本池仍在生命周期内的命令槽
     */
    private void completePlaceAdmissionSubmission(OrderCommand pending) {
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
     * 控制命令准备有序撤单任务；普通命令要求已有直达事件，再按操作取固定输入、活动索引路由或替换准入，返回槽内固定任务。下单自己的先撤由准入门闩处理，避免再放共同列表执行。
     * @param pending 本池仍在生命周期内的命令槽
     * @param direct 命令槽已经拥有的直达结算事件，控制路径可为空
     */
    Supplier<?> prepareMatchingCommand(OrderCommand pending,
            com.surprising.aeron.service.settlement.TradeSettlement direct) {
        if (isControlCommand(pending)) return prepareControlCancellations(pending);
        java.util.Objects.requireNonNull(direct, "trading command requires its direct settlement slot");
        pending.matcherPreCancellations(pending.operation() == OrderCommand.Operation.PLACE
                && pending.placeAdmission() != null ? List.of() : preMatchingCancellationOrders(pending));
        // 当前命令头的请求用户编号。
        long userId = pending.command().header().userId();
        // 当前静态 Matcher 分片号。
        int shard = matcherShard(pending);
        return switch (pending.operation()) {
            case PLACE -> pending.preparePlaceMatching(owner, shard, userId,
                    java.util.Objects.requireNonNull(pending.placeAdmission(), "place admission receipt").matchingOrder(),
                    direct);
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
            case TRIGGER -> pending.prepareAdmittedPlaceMatching(owner, shard, userId,
                    java.util.Objects.requireNonNull(pending.triggerAdmissionOrder(), "trigger admission receipt"), direct);
            default -> throw new IllegalStateException("unsupported trading operation " + pending.operation());
        };
    }

    /**
     * 只把单笔强平、批次强平和到期结算归为控制撮合，不包括普通下单/撤单/改单/触发子单。
     * @param pending 本池仍在生命周期内的命令槽
     */
    private boolean isControlCommand(OrderCommand pending) {
        return pending.operation() == OrderCommand.Operation.LIQUIDATION
                || pending.operation() == OrderCommand.Operation.LIQUIDATION_BATCH
                || pending.operation() == OrderCommand.Operation.SETTLEMENT;
    }

    /**
     * 按强平校验、批次预算或到期进度选出当前需撤订单，再把已确定前置撤单放在最前面，准备槽内控制任务；本方法本身不操作订单簿。
     * @param pending 本池仍在生命周期内的命令槽
     */
    private Supplier<?> prepareControlCancellations(OrderCommand pending) {
        // 当前选中或准备交给 Matcher 的有序撤单身份。
        List<CancellationOrder> orders;
        switch (pending.operation()) {
            case LIQUIDATION -> {
                // 当前操作对应的已解码输入或准备的固定 Matcher 函数，具体类型见声明。
                var command = pending.decodedCommand().liquidation();
                // 账户校验收据确认的当前强平计划状态。
                var liquidation = pending.liquidationCheck().liquidation(0);
                orders = liquidation == null || !pending.liquidationCheck().executable(0) ? List.of()
                        : owner.activeOrderIndex().lifecycleOrders(liquidation.userId(), owner.identities().instrumentId(liquidation.symbolId()),
                                command.cursorOrderId(), command.maxOrders()).orders();
            }
            case LIQUIDATION_BATCH -> orders = batchCancellationOrders(pending);
            case SETTLEMENT -> {
                // 当前操作对应的已解码输入或准备的固定 Matcher 函数，具体类型见声明。
                var command = pending.decodedCommand().settlement();
                // 该品种当前到期结算进度，已完成撤单阶段时不再选挂单。
                var progress = owner.lifecycleProgress(command.instrumentId());
                orders = progress != null && progress.ordersComplete() ? List.of()
                        : owner.activeOrderIndex().lifecycleOrders(0, command.instrumentId(),
                                command.cursorOrderId(), command.maxOrders()).orders();
            }
            default -> throw new IllegalStateException("unsupported cancellation control");
        }
        return prepareControlCancellations(pending, orders);
    }

    /** 已选定撤单身份时直接准备控制任务，保留首次分片判定使用的同一列表。 */
    private Supplier<?> prepareControlCancellations(OrderCommand pending, List<CancellationOrder> orders) {
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
    List<CancellationOrder> preMatchingCancellationOrders(OrderCommand pending) {
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


public static final class Progress {
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
    public Progress(TradingCoreRuntime owner) {
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
        OrderCommand pending = owner.pendingMatching().get(sequence);
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
    public void submissionCompleted(OrderCommand pending) {
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
            OrderCommand head = owner.pendingMatching().get(owner.pendingMatching().firstSequence());
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
                OrderCommand pending = owner.pendingMatching().submissionHead(shard);
                if (pending == null) {
                    readyShardMask &= ~shardBit;
                    break;
                }
                // 普通 PLACE 的可空 Lane 准入事件。
                var admission = pending.placeAdmission();
                // 当前提交头的可空批次上下文。
                OrderBatchProcessor.PendingBatch orderBatch = pending.orderBatch;
                // 当前批次共用的可空流水准入事件。
                OrderBatchProcessor.PlaceBatchAdmissionEvent batchAdmission = orderBatch == null
                        ? null : orderBatch.placeBatchAdmissionEvent;
                if (pending != null && pending.hasMatchingRejection()) {
                    // 同步准入可能在登记分片提交头之前就拒绝；其拒绝由有序提交消费，不再给它重建撮合订单。
                    readyShardMask &= ~shardBit;
                    break;
                }
                if (admission == null && batchAdmission == null) {
                    if (orderBatch != null && pending.clusterIndependent
                            && orderBatch.kind == OrderBatchProcessor.Kind.CANCEL && orderBatch.activated()
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
            OrderBatchProcessor.PendingBatch batch = owner.batches().firstBatch();
            // 全局顺序中最早的延后撮合项。
            OrderCommand deferredPending = owner.pendingMatching().firstDeferred();
            // 该延后项的可空 Core 序号，供与批次头比较。
            Long deferredSequence = deferredPending == null ? null : deferredPending.sequence();
            // 当前提交头、延后项或通知序号对应的命令槽。
            OrderCommand pending;
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
                OrderCommand pending = owner.pendingMatching().get(sequence);
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
        OrderCommand head = owner.pendingMatching().get(owner.pendingMatching().firstSequence());
        // 最早批次或当前队首关联的批次。
        OrderBatchProcessor.PendingBatch batch = head == null ? null : head.orderBatch;
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
        OrderCommand head = owner.pendingMatching().get(owner.pendingMatching().firstSequence());
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

}

final class ControlMatchingContinuation implements Supplier<Object> {
    private final OrderCommand slot;
    private TradingCoreRuntime owner;
    private List<CancellationOrder> orders;

    ControlMatchingContinuation(OrderCommand slot) {
        this.slot = Objects.requireNonNull(slot);
    }

    void prepare(TradingCoreRuntime owner, List<CancellationOrder> orders) {
        this.owner = Objects.requireNonNull(owner);
        this.orders = Objects.requireNonNull(orders);
    }

    void clear() {
        owner = null;
        orders = null;
    }

    @Override
    public Object get() {
        return owner.matchingAdapter().cancelControlOrders(
                slot.coreSequence,
                slot.command().header().commandId(),
                slot.command().header().submittedAtEpochMillis(),
                orders);
    }
}

final class AdmissionMatchingContinuation implements Supplier<Object> {
    private final OrderCommand slot;
    private TradingCoreRuntime owner;
    private OrderCommand.PlaceAdmissionEvent admission;
    private int matcherShard;
    private TradeSettlement settlement;
    private Supplier<?> original;

    AdmissionMatchingContinuation(OrderCommand slot) {
        this.slot = Objects.requireNonNull(slot);
    }

    void prepare(TradingCoreRuntime owner, OrderCommand.PlaceAdmissionEvent admission, int matcherShard,
                 TradeSettlement settlement, Supplier<?> original) {
        this.owner = Objects.requireNonNull(owner);
        this.admission = Objects.requireNonNull(admission);
        this.matcherShard = matcherShard;
        this.settlement = Objects.requireNonNull(settlement);
        this.original = Objects.requireNonNull(original);
    }

    void clear() {
        owner = null;
        admission = null;
        matcherShard = 0;
        settlement = null;
        original = null;
    }

    @Override
    public Object get() {
        admission.awaitMatcherReceipt(settlement);
        if (!settlement.admissionAccepted()) {
            var place = slot.decodedCommand().placeOrder();
            owner.matchingAdapter().rejectedPlaceDirect(
                    matcherShard, slot.coreSequence, slot.command().header().commandId(), place.orderId(),
                    slot.command().header().submittedAtEpochMillis(),
                    settlement.admissionResultCode(), settlement);
            return settlement;
        }
        var cancellations = settlement.admissionCancellations();
        var place = slot.decodedCommand().placeOrder();
        for (int index = 0; index < cancellations.size(); index++) {
            long orderId = cancellations instanceof ImmutableLongArrayList primitive
                    ? primitive.valueAt(index) : cancellations.get(index);
            var result = owner.matchingAdapter().cancelDirectPrefix(
                    slot.command().header().submittedAtEpochMillis(), slot.command().header().userId(),
                    orderId, admission.matchingOrder().instrument().instrumentIdValue());
            if (result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.MATCHING_UNKNOWN_ORDER_ID) {
                continue;
            }
            if (result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                    || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED) {
                settlement.recordDirectPreCancellation(orderId);
            } else {
                return owner.matchingAdapter().publishDirectPrefixFailure(
                        matcherShard, slot.coreSequence, slot.command().header().commandId(), place.orderId(), result, settlement);
            }
        }
        return original.get();
    }
}

final class OrderMatchingContinuation implements Supplier<Object> {
    private enum Action { PLACE, CANCEL, REPLACE }
    private final OrderCommand slot;
    private TradingCoreRuntime owner;
    private Action action;
    private int matcherShard;
    private long userId;
    private long orderId;
    private long originalOrderId;
    private int permanentSymbolId;
    private OrderCommand.ResolvedPlaceOrder resolvedOrder;
    private Order admittedOrder;
    private TradeSettlement direct;

    OrderMatchingContinuation(OrderCommand slot) {
        this.slot = Objects.requireNonNull(slot);
    }

    void preparePlace(TradingCoreRuntime owner, int matcherShard, long userId,
                 OrderCommand.ResolvedPlaceOrder resolvedOrder, Order admittedOrder, TradeSettlement direct) {
        if ((resolvedOrder == null) == (admittedOrder == null)) {
            throw new IllegalArgumentException("exactly one admitted PLACE order representation is required");
        }
        this.owner = Objects.requireNonNull(owner);
        this.action = Action.PLACE;
        this.matcherShard = matcherShard;
        this.userId = userId;
        this.resolvedOrder = resolvedOrder;
        this.admittedOrder = admittedOrder;
        this.direct = Objects.requireNonNull(direct, "direct settlement");
    }

    void prepareCancel(TradingCoreRuntime owner, int matcherShard, long orderId,
            long userId, String instrumentId, TradeSettlement direct) {
        this.owner = Objects.requireNonNull(owner);
        this.action = Action.CANCEL;
        this.matcherShard = matcherShard;
        this.orderId = orderId;
        this.userId = userId;
        this.permanentSymbolId = com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
        this.direct = Objects.requireNonNull(direct, "direct settlement");
    }

    void prepareReplace(TradingCoreRuntime owner, int matcherShard, long orderId,
            long userId, long originalOrderId, String instrumentId, OrderCommand.ResolvedPlaceOrder replacement,
            TradeSettlement direct) {
        this.owner = Objects.requireNonNull(owner);
        this.action = Action.REPLACE;
        this.matcherShard = matcherShard;
        this.orderId = orderId;
        this.userId = userId;
        this.originalOrderId = originalOrderId;
        this.permanentSymbolId = com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
        this.resolvedOrder = Objects.requireNonNull(replacement);
        this.direct = Objects.requireNonNull(direct);
    }

    void clear() {
        owner = null;
        action = null;
        matcherShard = 0;
        userId = 0;
        orderId = 0;
        originalOrderId = 0;
        permanentSymbolId = 0;
        resolvedOrder = null;
        admittedOrder = null;
        direct = null;
    }

    @Override
    public Object get() {
        if (action == null) throw new IllegalStateException("matching task is not prepared");
        long logicalOrderId = action == Action.PLACE
                ? admittedOrder != null ? admittedOrder.orderId() : resolvedOrder.orderId() : orderId;
        var failure = slot.applyMatcherPreCancellations(owner, matcherShard, logicalOrderId, direct);
        if (failure != null) return failure;
        long msb = slot.command().header().commandIdMsb();
        long lsb = slot.command().header().commandIdLsb();
        return switch (action) {
            case PLACE -> admittedOrder != null
                    ? owner.matchingAdapter().placeAdmittedDirect(matcherShard, slot.coreSequence, msb, lsb,
                            slot.command().header().submittedAtEpochMillis(), userId, admittedOrder, direct)
                    : owner.matchingAdapter().placeDirect(matcherShard, slot.coreSequence, msb, lsb,
                            slot.command().header().submittedAtEpochMillis(), userId, resolvedOrder, direct);
            case CANCEL -> owner.matchingAdapter().cancelDirect(matcherShard, slot.coreSequence, msb, lsb,
                    orderId, slot.command().header().submittedAtEpochMillis(), userId, permanentSymbolId, direct);
            case REPLACE -> owner.matchingAdapter().replaceDirect(matcherShard, slot.coreSequence, msb, lsb,
                    orderId, slot.command().header().submittedAtEpochMillis(), userId, originalOrderId,
                    permanentSymbolId, resolvedOrder, direct);
        };
    }
}

final class SettlementControlContinuation implements BooleanSupplier {
    private OrderCommitService coordinator;
    private com.surprising.aeron.service.settlement.ContractExpirySettlementService.SettlementWork work;

    void prepare(OrderCommitService coordinator,
                 com.surprising.aeron.service.settlement.ContractExpirySettlementService.SettlementWork work) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.work = Objects.requireNonNull(work);
    }

    void clear() {
        coordinator = null;
        work = null;
    }

    @Override
    public boolean getAsBoolean() {
        if (!work.poll()) return false;
        coordinator.owner().resultBuilder.beginChangedUsers();
        for (long user : work.selectedUsers()) {
            coordinator.owner().resultBuilder.addChangedUser(user);
        }
        coordinator.owner().resultBuilder.commandSettlementProgress = work.result();
        coordinator.publication.request();
        return true;
    }
}

final class LiquidationExecutionContinuation implements BooleanSupplier {
    private OrderCommitService coordinator;
    private com.surprising.aeron.service.liquidation.LiquidationService.ExecutionWork work;
    private com.surprising.aeron.protocol.CoreLiquidationProgressView progress;

    void prepare(OrderCommitService coordinator,
                 com.surprising.aeron.service.liquidation.LiquidationService.ExecutionWork work,
                 com.surprising.aeron.protocol.CoreLiquidationProgressView progress) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.work = Objects.requireNonNull(work);
        this.progress = Objects.requireNonNull(progress);
    }

    void clear() {
        coordinator = null;
        work = null;
        progress = null;
    }

    @Override
    public boolean getAsBoolean() {
        if (!work.getAsBoolean()) return false;
        coordinator.owner().resultBuilder.commandLiquidationProgress = progress;
        coordinator.publication.request();
        return true;
    }
}

