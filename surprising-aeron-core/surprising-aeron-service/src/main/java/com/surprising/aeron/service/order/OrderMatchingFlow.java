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
import com.surprising.aeron.service.core.TradingCore;
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

public final class OrderMatchingFlow {

    /** 组织本条交接链的所属交易运行时，不复制各线程业务事实。 */
    private final TradingCore owner;

    /** 保存非空所属运行时引用，不创建第二份交易状态。 */
    public OrderMatchingFlow(TradingCore owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /** 若已有待命令，仍占新 Core 序号并登记有序拒绝项，先返回 MATCHING_PENDING，等待前序退休。队列为空时直接记录源水位和幂等拒绝结果后返回。 */
    CoreResponse recordRejectedMatching(CoreMessage message, TradingCore.SourceKey sourceKey,
                                        CommandFingerprint fingerprint, CoreResultCode resultCode) {
        if (!owner.pendingMatching().isEmpty()) {
            long sequence = owner.nextAppliedSequence();
            OrderCommand pending = owner.orderChecks().newPendingMatching(sequence,
                    OrderCheckService.matchingOperation(message.header().messageType()),
                    message, fingerprint, owner.decodeMatchingCommand(message));
            putPendingMatching(pending);
            pending.rejectMatching(resultCode);
            owner.pendingMatching().completeSubmission(sequence);
            owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
            return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK,
                    CoreResultCode.MATCHING_PENDING, sequence,
                    TradingCore.EMPTY_RESPONSE_DATA);
        }
        long sequence = owner.nextAppliedSequence();
        owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
        owner.commandResultLog().storeOwnedResult(message.header().commandIdMsb(), message.header().commandIdLsb(), fingerprint,
                ResponseStatus.REJECTED, resultCode, sequence,
                TradingCore.EMPTY_RESPONSE_DATA);
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                sequence, TradingCore.EMPTY_RESPONSE_DATA);
    }

    /** 退休前解除生命周期范围，确认替换/下单准入已收齐，必要时取消已无后续执行的 Matcher 等待并释放收据。返回可复用原槽，异常不会回滚此前已解除的辅助记录。 */
    public OrderCommand removePendingMatching(long sequence) {
        owner.orderChecks().releasePendingLifecycle(sequence);
        int shard = owner.pendingMatching().submissionShard(sequence);
        boolean releasesSubmissionHead = shard >= 0
                && owner.pendingMatching().isSubmissionHead(sequence, shard);
        OrderCommand current = owner.pendingMatching().get(sequence);
        if (current != null) {
            if (current.replacementOrderCheck() != null) {
                if (!current.replacementOrderCheck().complete())
                    throw new IllegalStateException("terminal command has incomplete replacement admission");
                owner.accountBook().releaseReplaceOrderCheck(current.takeReplaceOrderCheck());
            }
            if (current.placeOrderCheck() != null && !current.placeOrderCheckOwnerCollected()
                    && !collectPlaceOrderCheckIfReady(current)) {
                throw new IllegalStateException("terminal command has an incomplete place admission");
            }
            current.committed();
            if (current.placeOrderCheck() != null && !current.placeOrderCheck().matcherConsumed()) {
                current.placeOrderCheck().cancelMatcherWait();
            }
            releasePlaceOrderCheckIfConsumed(current);
            if (current.placeOrderCheck() != null) {
                throw new IllegalStateException("terminal command still owns an unconsumed place admission");
            }
        }
        OrderCommand removed = owner.pendingMatching().remove(sequence);
        if (releasesSubmissionHead) owner.matchingProgress.submissionHeadReleased(shard);
        owner.refreshCommittedCoreSequence();
        return removed;
    }

    /** 只检查序号仍在待队列且高于已提交水位，不在此递增或写入已提交水位。 */
    public void commitMatchingSequence(long sequence) {
        if (!owner.pendingMatching().contains(sequence) || sequence <= owner.committedCoreSequence()) {
            throw new IllegalStateException("matching sequence is not pending above the committed watermark");
        }
    }

    /** 固定命令的 Cluster 独立模式，可复用已准备分片，然后入队并为非批量命令登记分片提交顺序。 */
    void putPendingMatching(OrderCommand pending) {
        pending.clusterIndependent = owner.clusterPipelineOrderCheck();
        if (owner.pendingMatching().size() >= owner.pendingMatching().capacity()) {
            throw new IllegalStateException("matching pending capacity is exhausted");
        }
        try {
            if (owner.clusterPipelineOrderCheck()
                    && owner.preparedRouteUserLaneBit() != 0
                    && owner.preparedRouteMatcherShard() >= 0) {
                pending.cachedMatcherShard(owner.preparedRouteMatcherShard());
            }
            owner.pendingMatching().put(pending);
            CoreMessageType type = pending.command().header().messageType();
            if (!TradingCore.isOrderBatchCommand(type)) {
                owner.pendingMatching().registerSubmission(pending.sequence(), matcherShard(pending));
            }
        } catch (RuntimeException failure) {
            if (owner.pendingMatching().remove(pending.sequence()) == null) {
                owner.pendingMatching().discardPrepared(pending.sequence());
            }
            throw failure;
        }
    }

    /** 优先返回槽缓存，否则从生命周期或普通命令路由取得品种并计算分片。 */
    public int matcherShard(OrderCommand pending) {
        int cached = pending.cachedMatcherShard();
        if (cached >= 0) return cached;
        String instrumentId = pending.operation() == OrderCommand.Operation.LIQUIDATION
                || pending.operation() == OrderCommand.Operation.LIQUIDATION_BATCH
                || pending.operation() == OrderCommand.Operation.SETTLEMENT
                ? owner.orderChecks().pendingLifecycleSymbol(pending)
                : owner.orderChecks().matchingSymbol(pending.operation(), pending.decodedCommand());
        int shard = instrumentId == null || instrumentId.isBlank() ? 0 : owner.matchingAdapter().matcherShardId(instrumentId);
        pending.cachedMatcherShard(shard);
        return shard;
    }

    /** 按强平批次动作顺序与账户校验收据挑出 PLANNED/ORDERED 且需要撤单的工作，使用剩余撤单预算分页，返回固定外层列表，不再执行账户强平。 */
    List<CancellationOrder> batchCancellationOrders(OrderCommand pending) {
        var command = pending.decodedCommand().liquidationBatch();
        List<CancellationOrder> orders = new ArrayList<>();
        int remaining = command.maxCancelOrders();
        for (int index = 0; index < command.actions().size(); index++) {
            if (remaining == 0) break;
            if (!pending.liquidationCheck().cancelsOrders(index)) continue;
            var action = command.actions().get(index);
            var liquidation = pending.liquidationCheck().liquidation(index);
            if (liquidation == null || (liquidation.status() != CoreLiquidationState.Status.PLANNED
                    && liquidation.status() != CoreLiquidationState.Status.ORDERED)) continue;
            ActiveOrderIndex.LifecycleOrderChunk chunk = owner.activeOrderIndex().lifecycleOrders(
                    liquidation.userId(), owner.identities().instrumentId(liquidation.symbolId()),
                    action.cursorOrderId(), remaining);
            orders.addAll(chunk.orders());
            remaining -= chunk.orders().size();
        }
        return orders;
    }

    /** 等待本分片提交头及替换/强平校验，准备 Lane 结果目标。未就绪时直接返回，不在 Owner 阻塞等 Lane。 */
    void submitMatching(OrderCommand pending) {
        boolean batchContinuation = pending.orderBatch != null && pending.orderBatch.commitStarted()
                && pending.orderBatch.nextIndex > 0 && pending.isMatchingSubmitted();
        if (pending.crossShardCancellationStarted
                || !batchContinuation && owner.matchingProgress.submissionDeferred(pending.sequence())) return;
        if (!collectReplaceOrderCheckIfReady(pending) || hasPendingMatchingRejection(pending.sequence())) return;
        if (!collectLiquidationChecksIfReady(pending) || pending.hasMatchingRejection()) return;
        pending.prepareLaneResultTarget(owner.responseSlotPool());
        if (pending.orderBatch != null) {
            owner.batches().submitOrderBatchMatching(pending);
            if (pending.isMatchingSubmitted()
                    && owner.pendingMatching().submissionShard(pending.sequence()) >= 0
                    && owner.batches().remainingMatcherShardMask(pending.orderBatch) == 0)
                owner.matchingProgress.submissionCompleted(pending);
            return;
        }
        if (pending.operation() == OrderCommand.Operation.LIQUIDATION_BATCH
                && owner.matchingAdapter().topology().matchingEngineCount() > 1) {
            List<CancellationOrder> orders = batchCancellationOrders(pending);
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
        var direct = owner.prepareForMatching(pending);
        var command = prepareMatchingCommand(pending, direct);
        java.util.function.Supplier<?> matcherSubmission = command;
        if (direct != null && pending.placeOrderCheck() != null) {
            matcherSubmission = pending.gateOrderCheck(owner, pending.placeOrderCheck(), matcherShard(pending),
                    direct, matcherSubmission);
        }
        owner.matcherPipeline().submit(matcherShard(pending), pending.sequence(), matcherSubmission, direct);
        pending.matchingSubmitted();
        if (pending.placeOrderCheck() == null) owner.matchingProgress.submissionCompleted(pending);
    }

    /** 空列表返回负一，全部同分片返回该号，遇到混合分片返回负二。 */
    int singleMatcherShard(List<CancellationOrder> orders) {
        int shardId = -1;
        for (var order : orders) {
            int orderShard = owner.matchingAdapter().topology().matcherShardId(order.symbolId());
            if (shardId == -1) shardId = orderShard;
            else if (shardId != orderShard) return -2;
        }
        return shardId;
    }

    /** 普通命令用缓存静态路由，批次按当前批次项的目标分片决定提交顺序。 */
    int pendingSubmissionShard(OrderCommand pending) {
        OrderBatchProcessor.PendingBatch batch = pending.orderBatch;
        return batch == null ? matcherShard(pending) : owner.batches().orderBatchMatcherShard(batch);
    }

    /** 要求同步辅助结果非空且 Core 序号匹配，再保存到严格占用槽并增加调度修订，已有结果的保留规则由槽实现决定。 */
    public void publishMatchingCompletion(long sequence, OrderMatchingResult.Control result) {
        if (result == null || result.nativeCoreSequence() != sequence) {
            throw new IllegalStateException("synchronous matcher returned an invalid result");
        }
        owner.pendingMatching().required(sequence).publishMatchingCompletion(result);
        owner.pendingMatching().progressChanged();
    }

    /** 准入未完成检查 Lane 健康后返回假。 */
    boolean collectReplaceOrderCheckIfReady(OrderCommand pending) {
        var event = pending.replacementOrderCheck();
        if (event == null || pending.matchingOrderCheck() != null || pending.hasMatchingRejection()) return true;
        if (!event.complete()) { owner.accountBook().assertAccountLanesHealthy(); return false; }
        RuntimeException rejection = event.rejection();
        try {
            if (rejection != null) throw rejection;
            var orderCheck = event.orderCheck();
            if (orderCheck.resolved().orderId() != orderCheck.originalOrderId())
                owner.requireOrderIdentityAvailable(orderCheck.userId(), orderCheck.command());
            pending.withOrderCheck(orderCheck);
            pending.withPreMatchingCancellations(pending.replacementOrderCheck().closeCapacityCancellations());
        } catch (com.surprising.aeron.exception.CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            pending.rejectMatching(failure instanceof com.surprising.aeron.exception.CoreStateRejectedException rejected
                    ? CoreResultCode.fromRejectionCode(rejected.code())
                    : failure instanceof ArithmeticException ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
            owner.matchingProgress.submissionCompleted(pending);
        }
        return true;
    }

    private boolean collectLiquidationChecksIfReady(OrderCommand pending) {
        if (pending.operation() != OrderCommand.Operation.LIQUIDATION
                && pending.operation() != OrderCommand.Operation.LIQUIDATION_BATCH) return true;
        var check = pending.liquidationCheck();
        if (!check.prepared()) {
            if (pending.operation() == OrderCommand.Operation.LIQUIDATION)
                check.prepare(pending.decodedCommand().liquidation(), owner.accountBook(), owner.identities());
            else check.prepare(pending.decodedCommand().liquidationBatch(), owner.accountBook(), owner.identities());
        }
        try { if (!check.poll()) return false; }
        catch (CoreStateRejectedException rejection) {
            pending.rejectMatching(CoreResultCode.fromRejectionCode(rejection.code()));
            owner.matchingProgress.submissionCompleted(pending);
            return true;
        }
        if (pending.operation() == OrderCommand.Operation.LIQUIDATION) {
            try { check.executable(0); }
            catch (CoreStateRejectedException rejection) {
                pending.rejectMatching(CoreResultCode.fromRejectionCode(rejection.code()));
                owner.matchingProgress.submissionCompleted(pending);
            }
        }
        return true;
    }

    /** 只在第一次 Owner 收据收集时保存准入或拒绝，随后推进分片提交顺序。 */
    public boolean collectPlaceOrderCheckIfReady(OrderCommand pending) {
        if (pending == null || pending.placeOrderCheck() == null) return true;
        var orderCheck = pending.placeOrderCheck();
        if (!orderCheck.complete()) return false;
        RuntimeException rejection = orderCheck.rejection();
        if (!pending.placeOrderCheckOwnerCollected()) {
            if (rejection != null) {
                CoreResultCode resultCode = rejection instanceof CoreStateRejectedException rejected
                        ? CoreResultCode.fromRejectionCode(rejected.code())
                        : rejection instanceof ArithmeticException ? CoreResultCode.ARITHMETIC_OVERFLOW
                        : CoreResultCode.INVALID_COMMAND;
                pending.rejectMatching(resultCode);
            } else {
                OrderCommand.ResolvedPlaceOrder admitted = owner.accountBook().collectPlaceOrderCheck(orderCheck);
                if (owner.realtimeCapture() != null) pending.realtimeResolvedTakerOrder(admitted);
            }
            pending.markPlaceOrderCheckOwnerCollected();
        }
        completePlaceOrderCheckSubmission(pending);
        if (!pending.isMatchingSubmitted() && rejection != null && !orderCheck.matcherConsumed()) {
            orderCheck.cancelMatcherWait();
        }
        return true;
    }

    /** 只有 Owner 已收齐且 Matcher 已消费该收据时，取走并归还下单准入事件。 */
    void releasePlaceOrderCheckIfConsumed(OrderCommand pending) {
        if (pending != null && pending.placeOrderCheckOwnerCollected()
                && pending.placeOrderCheck() != null && pending.placeOrderCheck().matcherConsumed()) {
            owner.accountBook().releasePlaceOrderCheck(pending.takePlaceOrderCheck());
        }
    }

    private void completePlaceOrderCheckSubmission(OrderCommand pending) {
        if (pending != null && pending.isMatchingSubmitted()
                && owner.pendingMatching().submissionShard(pending.sequence()) >= 0) {
            owner.matchingProgress.submissionCompleted(pending);
        }
    }

    /** 检查槽池仍占用完整序号且已登记拒绝码，不要求该槽此刻一定已链接到全局队列。 */
    public boolean hasPendingMatchingRejection(long sequence) {
        return owner.pendingMatching().claimed(sequence)
                && owner.pendingMatching().required(sequence).hasMatchingRejection();
    }

    /** 控制命令准备有序撤单任务。交易命令把槽上已有的先撤路由交给 Matcher。 */
    Supplier<?> prepareMatchingCommand(OrderCommand pending,
            com.surprising.aeron.service.settlement.TradeSettlement direct) {
        if (isControlCommand(pending)) return prepareControlCancellations(pending);
        java.util.Objects.requireNonNull(direct, "trading command requires its direct settlement slot");
        pending.matcherPreCancellations(preMatchingCancellationOrders(pending));
        long userId = pending.command().header().userId();
        int shard = matcherShard(pending);
        return switch (pending.operation()) {
            case PLACE -> pending.preparePlaceMatching(owner, shard, userId,
                    java.util.Objects.requireNonNull(pending.placeOrderCheck(), "place admission receipt").matchingOrder(),
                    direct);
            case CANCEL -> {
                var command = pending.decodedCommand().cancelOrder();
                var route = java.util.Objects.requireNonNull(owner.activeOrderIndex().activeOrderRoute(command.orderId()),
                        "admitted cancellation route");
                yield pending.prepareCancelMatching(owner, shard, command.orderId(), userId, route.instrumentId(), direct);
            }
            case REPLACE, AMEND -> {
                var orderCheck = owner.requireMatchingOrderCheck(pending);
                yield pending.prepareReplaceMatching(owner, shard, orderCheck.resolved().orderId(), userId,
                        orderCheck.originalOrderId(), orderCheck.command().instrumentId(), orderCheck.resolved(), direct);
            }
            case TRIGGER -> pending.prepareAdmittedPlaceMatching(owner, shard, userId,
                    java.util.Objects.requireNonNull(pending.triggerOrderCheckOrder(), "trigger admission receipt"), direct);
            default -> throw new IllegalStateException("unsupported trading operation " + pending.operation());
        };
    }

    private boolean isControlCommand(OrderCommand pending) {
        return pending.operation() == OrderCommand.Operation.LIQUIDATION
                || pending.operation() == OrderCommand.Operation.LIQUIDATION_BATCH
                || pending.operation() == OrderCommand.Operation.SETTLEMENT;
    }

    private Supplier<?> prepareControlCancellations(OrderCommand pending) {
        List<CancellationOrder> orders;
        switch (pending.operation()) {
            case LIQUIDATION -> {
                var command = pending.decodedCommand().liquidation();
                var liquidation = pending.liquidationCheck().liquidation(0);
                orders = liquidation == null || !pending.liquidationCheck().executable(0) ? List.of()
                        : owner.activeOrderIndex().lifecycleOrders(liquidation.userId(), owner.identities().instrumentId(liquidation.symbolId()),
                                command.cursorOrderId(), command.maxOrders()).orders();
            }
            case LIQUIDATION_BATCH -> orders = batchCancellationOrders(pending);
            case SETTLEMENT -> {
                var command = pending.decodedCommand().settlement();
                var progress = owner.lifecycleProgress(command.instrumentId());
                orders = progress != null && progress.ordersComplete() ? List.of()
                        : owner.activeOrderIndex().lifecycleOrders(0, command.instrumentId(),
                                command.cursorOrderId(), command.maxOrders()).orders();
            }
            default -> throw new IllegalStateException("unsupported cancellation control");
        }
        return prepareControlCancellations(pending, orders);
    }

    private Supplier<?> prepareControlCancellations(OrderCommand pending, List<CancellationOrder> orders) {
        var prefix = preMatchingCancellationOrders(pending);
        if (!prefix.isEmpty()) {
            var combined = new ArrayList<CancellationOrder>(prefix.size() + orders.size());
            combined.addAll(prefix); combined.addAll(orders); orders = combined;
        }
        return pending.prepareControlMatching(owner, orders);
    }

    /** 把先撤订单号按当前活动索引转成路由身份，已经没有路由的订单跳过，空结果共用空表。 */
    List<CancellationOrder> preMatchingCancellationOrders(OrderCommand pending) {
        List<Long> orderIds = pending.preMatchingCancellationOrderIds();
        if (orderIds.isEmpty()) return List.of();
        ArrayList<CancellationOrder> orders = new ArrayList<>(orderIds.size());
        for (long orderId : orderIds) {
            var order = owner.activeOrderIndex().activeOrderRoute(orderId);
            if (order != null) {
                orders.add(new CancellationOrder(order.orderId(), order.userId(), order.instrumentId()));
            }
        }
        return orders.isEmpty() ? List.of() : orders;
    }

public static final class Progress {
    /** 当前 Owner 运行时及其现有队列/事件入口。 */
    private final TradingCore owner;
    private long readyShardMask;
    private long nextHealthCheckNs = System.nanoTime();

    /** 保存非空所属运行时，使用既有命令和事件结构推进，不创建新工作线程。 */
    public Progress(TradingCore owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /** 合法非负分片释放提交头后置就绪位，外层保证分片在 long 位范围内。 */
    void submissionHeadReleased(int shard) {
        if (shard >= 0) readyShardMask |= 1L << shard;
    }

    /** 检查各分片是否仍有提交头，有则置位，提示批量结束后重试后继命令。 */
    void batchCompleted() {
        for (int shard = 0; shard < owner.matchingAdapter().topology().matchingEngineCount(); shard++) {
            if (owner.pendingMatching().submissionHead(shard) != null) readyShardMask |= 1L << shard;
        }
    }

    /** 不是本分片提交头则延后。 */
    boolean submissionDeferred(long sequence) {
        OrderCommand pending = owner.pendingMatching().get(sequence);
        if (pending != null) {
            int shardId = owner.matchingFlow.pendingSubmissionShard(pending);
            if (!owner.pendingMatching().isSubmissionHead(sequence, shardId)) return true;
        }
        if (!owner.batches().hasPendingBatches() || sequence <= owner.batches().firstBatch().sequence)
            return false;
        return pending == null || !pending.clusterIndependent
                || (owner.batches().remainingMatcherShardMask(owner.batches().firstBatch())
                    & (1L << owner.matchingFlow.pendingSubmissionShard(pending))) != 0;
    }

    /** 更新调度修订、解除当前分片提交链接，若有批次、后继或延后项则置位重试。 */
    public void submissionCompleted(OrderCommand pending) {
        owner.pendingMatching().progressChanged();
        int shard = owner.matchingFlow.pendingSubmissionShard(pending);
        owner.pendingMatching().completeSubmission(pending.sequence());
        // 提交头释放后立即提示同分片后继，避免普通下单后的撤单或控制项还在等一个不会再来的 Lane 通知。
        if (pending.orderBatch != null || owner.pendingMatching().submissionHead(shard) != null
                || owner.pendingMatching().hasDeferred()) {
            readyShardMask |= 1L << shard;
        }
    }

    /** 先收准入就绪分片提示，逐就绪分片只推进提交头。失败则清理准备数据，单项保留已知拒绝，多项转顺序准入。普通 PLACE 可以先提交 Matcher 等收据，顺序批次项仍由批次执行器拥有游标。 */
    public void progressPlaceBatchOrderChecks() {
        drainPlaceOrderCheckNotifications();
        if (readyShardMask == 0 && hasDeferredMatchingSubmission()) {
            OrderCommand head = owner.pendingMatching().get(owner.pendingMatching().firstSequence());
            readyShardMask |= 1L << owner.matchingFlow.pendingSubmissionShard(head);
        }
        long readyShards = readyShardMask;
        while (readyShards != 0) {
            int shard = Long.numberOfTrailingZeros(readyShards);
            long shardBit = 1L << shard;
            readyShards &= ~shardBit;
            while ((readyShardMask & shardBit) != 0) {
                OrderCommand pending = owner.pendingMatching().submissionHead(shard);
                if (pending == null) {
                    readyShardMask &= ~shardBit;
                    break;
                }
                var orderCheck = pending.placeOrderCheck();
                OrderBatchProcessor.PendingBatch orderBatch = pending.orderBatch;
                OrderBatchProcessor.PlaceBatchCheckEvent batchOrderCheck = orderBatch == null
                        ? null : orderBatch.placeBatchOrderCheckEvent;
                if (pending != null && pending.hasMatchingRejection()) {
                    readyShardMask &= ~shardBit;
                    break;
                }
                if (orderCheck == null && batchOrderCheck == null) {
                    if (orderBatch != null && pending.clusterIndependent
                            && orderBatch.kind == OrderBatchProcessor.Kind.CANCEL && orderBatch.activated()
                            && !pending.isMatchingSubmitted()) {
                        owner.matchingFlow.submitMatching(pending);
                    }
                    // 顺序批次自己的项游标仍归批次执行器，不能在这里重复提交。
                    if (orderBatch == null && !pending.isMatchingSubmitted() && !pending.deferredMatching()) {
                        owner.matchingFlow.submitMatching(pending);
                        if (pending.isMatchingSubmitted()) continue;
                    }
                    readyShardMask &= ~shardBit;
                    break;
                }
                if (batchOrderCheck != null) {
                    if (!batchOrderCheck.complete()) {
                        // 就绪位只是合并提示，事件未完成时保留，避免同分片较晚通知合并后被清掉。
                        break;
                    }
                    RuntimeException rejection = batchOrderCheck.rejection();
                    if (rejection != null) {
                        owner.accountBook().discardPlaceBatchOrderCheck(batchOrderCheck);
                        owner.accountBook().releasePlaceBatchOrderCheck(batchOrderCheck);
                        orderBatch.placeBatchOrderCheckEvent = null;
                        owner.batches().unregisterPipelinedBatchSymbols(orderBatch);
                        orderBatch.clearPreparedDecisionCache();
                        orderBatch.pipelined = false;
                        orderBatch.sequentialOrderCheck = true;
                        // 只有一项时，Lane 已校验并回滚，无需重做部分批次准入。
                        if (orderBatch.items.size() == 1
                                && (rejection instanceof CoreStateRejectedException
                                || rejection instanceof ArithmeticException
                                || rejection instanceof IllegalArgumentException)) {
                            CoreResultCode code = rejection instanceof CoreStateRejectedException stateRejection
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
                    if (!orderBatch.orderCheckCollected()) {
                        owner.accountBook().registerPlaceBatchOrderCheck(batchOrderCheck);
                        orderBatch.orderCheckCollected(true);
                    }
                    if (!pending.isMatchingSubmitted()) {
                        owner.batches().submitPipelinedPlaceBatch(pending, orderBatch);
                        pending.matchingSubmitted();
                        if (owner.batches().remainingMatcherShardMask(orderBatch) == 0)
                            submissionCompleted(pending);
                    }
                    if (owner.pendingMatching().isSubmissionHead(pending.sequence(), shard)) {
                        readyShardMask &= ~shardBit;
                        break;
                    }
                    continue;
                }
                if (!pending.isMatchingSubmitted()) {
                    owner.matchingFlow.submitMatching(pending);
                    if (pending.isMatchingSubmitted()) continue;
                }
                readyShardMask &= ~shardBit;
                break;
            }
        }
    }

    /** 取清运行时账户准入的分片就绪位并合入本地提示，不在这里取走准入 payload。 */
    void drainPlaceOrderCheckNotifications() {
        readyShardMask |= owner.accountBook().takeAccountOrderCheckReadyShardMask();
    }

    /** 比较最早延后命令和未激活批次的 Core 序号，先推进较早项。流水批次可尝试并行激活，顺序准入必须等到全局队首，使用原时间、位置和来源恢复延后命令。 */
    public void submitDeferredMatchingAfterBatch() {
        while (!owner.pendingMatching().isEmpty()) {
            OrderBatchProcessor.PendingBatch batch = owner.batches().firstBatch();
            OrderCommand deferredPending = owner.pendingMatching().firstDeferred();
            Long deferredSequence = deferredPending == null ? null : deferredPending.sequence();
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
                CoreResponse response = owner.orderChecks().prepareMatching(pending.command(), pending.deferredClusterTimestamp(),
                        pending.deferredClusterPosition(), pending.deferredSourceKey(), pending.operation(),
                        pending.fingerprint(), pending);
                if (response == null) return;
                if (response.status() == ResponseStatus.REJECTED) continue;
                continue;
            }
        }
    }

    /** 检查跨分片撤单、分片就绪位、运行时/Matcher 通知或 Owner 本地续步是否仍需推进，不代表命令已经完成。 */
    public boolean hasDrainWork() {
        return owner.crossShardCancellations().hasPending()
                || readyShardMask != 0
                || owner.accountBook().hasMatchingNotifications()
                || owner.matcherPipeline().hasMatchingCompletions()
                || hasLocalMatchingWork();
    }

    /** 各推进一次跨分片控制、下单收据、分片提交及 Matcher/结算通知，更新待队列修订。 */
    public void drainMatchingCompletions() {
        if (owner.crossShardCancellations().hasPending()) owner.crossShardCancellations().poll();
        drainPlaceOrderCheckCompletions();
        if (readyShardMask != 0 || owner.accountBook().hasPlaceOrderCheckNotifications()
                || hasDeferredMatchingSubmission()) {
            progressPlaceBatchOrderChecks();
        }
        if (owner.matcherPipeline().hasMatchingCompletions()) {
            owner.matcherPipeline().drainMatchingCompletions();
            owner.pendingMatching().progressChanged();
        }
        if (owner.accountBook().hasSettlementNotifications()) owner.commits.drainMatcherSettlementCompletions();
    }

    /** 未提交项只唤起其分片，已提交项尝试由 Owner 收据收集并记进展，准入数据保留在原事件。 */
    private void drainPlaceOrderCheckCompletions() {
        long readyLanes = owner.accountBook().takePlaceOrderCheckReadyLaneMask();
        while (readyLanes != 0) {
            int laneId = Long.numberOfTrailingZeros(readyLanes);
            readyLanes &= readyLanes - 1;
            long sequence;
            while ((sequence = owner.accountBook().pollPlaceOrderCheckReady(laneId)) != 0) {
                OrderCommand pending = owner.pendingMatching().get(sequence);
                if (pending == null || pending.placeOrderCheck() == null) continue;
                if (!pending.isMatchingSubmitted()) {
                    readyShardMask |= 1L << owner.matchingFlow.pendingSubmissionShard(pending);
                } else if (owner.matchingFlow.collectPlaceOrderCheckIfReady(pending)) {
                    owner.pendingMatching().progressChanged();
                }
            }
        }
    }

    /** 判断没有新通知时是否仍有资金派发、批次激活/提交或可就绪队首等本地工作。 */
    public boolean hasLocalMatchingWork() {
        if (readyShardMask != 0 || owner.commits.hasFinancialDispatchWork()) return true;
        OrderCommand head = owner.pendingMatching().get(owner.pendingMatching().firstSequence());
        OrderBatchProcessor.PendingBatch batch = head == null ? null : head.orderBatch;
        // 交接和最终元数据提交可能没有新的结算通知，仍要推进这些有界 Owner 续步。
        return batch != null && (!batch.activated() || batch.laneCommitEvent != null || batch.itemOrderCheck != null)
                || head != null && !head.isMatchingSubmitted() && head.placeOrderCheck() == null
                && !head.deferredMatching()
                || head != null && owner.commits.matchingCommitReady(head);
    }

    private boolean hasDeferredMatchingSubmission() {
        OrderCommand head = owner.pendingMatching().get(owner.pendingMatching().firstSequence());
        return head != null && !head.hasMatchingRejection()
                && !head.isMatchingSubmitted() && !head.deferredMatching()
                && head.orderBatch == null;
    }

    /** 用当前 nanoTime 检查通知及周期性健康状态。 */
    public boolean hasNotifications() {
        return hasNotifications(System.nanoTime());
    }

    /** 空路径最多约每毫秒主动检查一次 Owner 健康，避免故障 Lane 没发通知而永远不被发现。 */
    public boolean hasNotifications(long now) {
        if (now - nextHealthCheckNs >= 0) {
            owner.assertHealthy();
            nextHealthCheckNs = now + 1_000_000L;
        }
        return owner.accountBook().hasMatchingNotifications() || owner.matcherPipeline().hasMatchingCompletions();
    }
}

}

final class ControlMatchCallback implements Supplier<Object> {
    private final OrderCommand slot;
    private TradingCore owner;
    private List<CancellationOrder> orders;

    ControlMatchCallback(OrderCommand slot) {
        this.slot = Objects.requireNonNull(slot);
    }

    void prepare(TradingCore owner, List<CancellationOrder> orders) {
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

final class AfterCheckMatchCallback implements Supplier<Object> {
    private final OrderCommand slot;
    private TradingCore owner;
    private OrderCommand.PlaceOrderCheckEvent orderCheck;
    private int matcherShard;
    private TradeSettlement settlement;
    private Supplier<?> original;

    AfterCheckMatchCallback(OrderCommand slot) {
        this.slot = Objects.requireNonNull(slot);
    }

    void prepare(TradingCore owner, OrderCommand.PlaceOrderCheckEvent orderCheck, int matcherShard,
                 TradeSettlement settlement, Supplier<?> original) {
        this.owner = Objects.requireNonNull(owner);
        this.orderCheck = Objects.requireNonNull(orderCheck);
        this.matcherShard = matcherShard;
        this.settlement = Objects.requireNonNull(settlement);
        this.original = Objects.requireNonNull(original);
    }

    void clear() {
        owner = null;
        orderCheck = null;
        matcherShard = 0;
        settlement = null;
        original = null;
    }

    @Override
    public Object get() {
        orderCheck.awaitMatcherReceipt(settlement);
        if (!settlement.orderCheckAccepted()) {
            var place = slot.decodedCommand().placeOrder();
            owner.matchingAdapter().rejectedPlaceDirect(
                    matcherShard, slot.coreSequence, slot.command().header().commandId(), place.orderId(),
                    slot.command().header().submittedAtEpochMillis(),
                    settlement.orderCheckResultCode(), settlement);
            return settlement;
        }
        var cancellations = settlement.orderCheckCancellations();
        var place = slot.decodedCommand().placeOrder();
        for (int index = 0; index < cancellations.size(); index++) {
            long orderId = cancellations instanceof ImmutableLongArrayList primitive
                    ? primitive.valueAt(index) : cancellations.get(index);
            var result = owner.matchingAdapter().cancelDirectPrefix(
                    slot.command().header().submittedAtEpochMillis(), slot.command().header().userId(),
                    orderId, orderCheck.matchingOrder().instrument().instrumentIdValue());
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

final class OrderMatchCallback implements Supplier<Object> {
    private enum Action { PLACE, CANCEL, REPLACE }
    private final OrderCommand slot;
    private TradingCore owner;
    private Action action;
    private int matcherShard;
    private long userId;
    private long orderId;
    private long originalOrderId;
    private int permanentSymbolId;
    private OrderCommand.ResolvedPlaceOrder resolvedOrder;
    private Order admittedOrder;
    private TradeSettlement direct;

    OrderMatchCallback(OrderCommand slot) {
        this.slot = Objects.requireNonNull(slot);
    }

    void preparePlace(TradingCore owner, int matcherShard, long userId,
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

    void prepareCancel(TradingCore owner, int matcherShard, long orderId,
            long userId, String instrumentId, TradeSettlement direct) {
        this.owner = Objects.requireNonNull(owner);
        this.action = Action.CANCEL;
        this.matcherShard = matcherShard;
        this.orderId = orderId;
        this.userId = userId;
        this.permanentSymbolId = com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
        this.direct = Objects.requireNonNull(direct, "direct settlement");
    }

    void prepareReplace(TradingCore owner, int matcherShard, long orderId,
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

final class SettlementCallback implements BooleanSupplier {
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

final class LiquidationCallback implements BooleanSupplier {
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

