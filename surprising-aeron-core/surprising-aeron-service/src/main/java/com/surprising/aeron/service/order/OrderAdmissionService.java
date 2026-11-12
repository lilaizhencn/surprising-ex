package com.surprising.aeron.service.order;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import com.surprising.aeron.support.math.*;
import com.surprising.aeron.support.math.CoreArithmetic;
import com.surprising.aeron.support.math.CoreContractMath;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

@RequiredArgsConstructor
public final class OrderAdmissionService {
    /** 唯一所属交易 Owner，业务推进及共享编排状态由其串行访问。 */
    final TradingCoreRuntime owner;

    LongObjectHashMap<PendingLifecycleRanges> pendingLifecycleScopes;
    private final java.util.ArrayDeque<PendingLifecycleRanges> lifecycleRangePool = new java.util.ArrayDeque<>();

    final List<QueuedTriggerMatching> queuedMatching = new ArrayList<>();

    private long[] batchScopeUsers = new long[8];
    private String[] batchScopeSymbols = new String[8];
    private int batchScopeCount;
    private final PrimitiveLongChangeSet batchChangedUsers = new PrimitiveLongChangeSet();
    private final PrimitiveLongChangeSet batchChangedOrders = new PrimitiveLongChangeSet();

    /** 触发业务释放账户访问权前保存的冻结子单输入，只存活到正式登记命令槽，不拥有账户或订单事实。 */
    @NoArgsConstructor
    static final class QueuedTriggerMatching {
        @Accessors(fluent = true, chain = false)
        @Getter
        private CoreMessage command;
        @Accessors(fluent = true, chain = false)
        @Getter
        private Order order;
        @Accessors(fluent = true, chain = false)
        @Getter
        private com.surprising.aeron.model.CoreTriggerOrderState trigger;

        /** 直接保存本条触发子单输入，不校验非空或复制订单。 */
        public QueuedTriggerMatching(CoreMessage command, Order order,
                com.surprising.aeron.model.CoreTriggerOrderState trigger) {
            set(command, order, trigger);
        }

        /** 绑定当前触发子单的命令、冻结订单和源触发单引用，不创建第二份订单索引。 */
        public void set(CoreMessage command, Order order,
                com.surprising.aeron.model.CoreTriggerOrderState trigger) {
            this.command = command;
            this.order = order;
            this.trigger = trigger;
        }

        /** 解除本条子单的三个引用，供下轮复用。 */
        public void clear() {
            this.command = null;
            this.order = null;
            this.trigger = null;
        }

    }

    private final QueuedTriggerMatching[] queuedMatchingPool = new QueuedTriggerMatching[64];
    private int queuedPoolIndex;

    /** 优先按游标借用前六十四个可复用暂存对象，超过池长则新建。 */
    public QueuedTriggerMatching borrowQueuedMatching(CoreMessage command, Order order,
            com.surprising.aeron.model.CoreTriggerOrderState trigger) {
        QueuedTriggerMatching entry;
        if (queuedPoolIndex < queuedMatchingPool.length) {
            entry = queuedMatchingPool[queuedPoolIndex];
            if (entry == null) {
                entry = new QueuedTriggerMatching();
                queuedMatchingPool[queuedPoolIndex] = entry;
            }
            queuedPoolIndex++;
            entry.set(command, order, trigger);
        } else {
            entry = new QueuedTriggerMatching(command, order, trigger);
        }
        queuedMatching.add(entry);
        return entry;
    }

    /** 逐项清除非空暂存对象引用，清列表并把池游标归零。 */
    public void clearQueuedMatching() {
        for (int i = 0; i < queuedMatching.size(); i++) {
            QueuedTriggerMatching q = queuedMatching.get(i);
            if (q != null) q.clear();
        }
        queuedMatching.clear();
        queuedPoolIndex = 0;
    }

    /** 逐个把冻结触发子单登记为新的 Core 序号，保存源触发及固定提交边界，再提交 Matcher。 */
    public void appendQueuedMatching(long clusterTimestamp, long clusterPosition) {
        if (queuedMatching.isEmpty()) return;
        for (int i = 0; i < queuedMatching.size(); i++) {
            QueuedTriggerMatching queued = queuedMatching.get(i);
            if (queued == null) continue;
            CoreMessage command = queued.command();
            long sequence = owner.nextAppliedSequence();
            OrderCommand pending = newPendingMatching(sequence, OrderCommand.Operation.TRIGGER, command);
            pending.triggerAdmission(queued.order());
            pending.sourceTrigger(queued.trigger());
            pending.establishCommitFence(clusterTimestamp, clusterPosition);
            owner.matchingFlow.putPendingMatching(pending);
            registerPendingLifecycle(pending);
            owner.recordAppliedSequence(sequence, null, 0L);
            owner.matchingFlow.submitMatching(pending);
            queued.clear();
        }
        queuedMatching.clear();
        queuedPoolIndex = 0;
    }

    /** 从原命令计算指纹，再借用新的待命令槽。 */
    OrderCommand newPendingMatching(long sequence, OrderCommand.Operation operation,
                                   CoreMessage command) {
        return owner.pendingMatching().acquire(sequence, operation, command, owner.fingerprints().of(command),
                List.of(), owner.decodeMatchingCommand(command), null);
    }

    /** 复用已经解码的输入借槽，前置撤单先为空。 */
    OrderCommand newPendingMatching(long sequence, OrderCommand.Operation operation,
                                   CoreMessage command, CommandFingerprint fingerprint,
                                   OrderCommand.Decoded decodedCommand) {
        return owner.pendingMatching().acquire(sequence, operation, command, fingerprint, List.of(),
                decodedCommand, null);
    }

    /** 确定撮合操作。已有未完成批次且不允许 Cluster 管线准入时先延后，否则立即进入本条命令准入。 */
    public CoreResponse beginMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                       TradingCoreRuntime.SourceKey sourceKey, CommandFingerprint fingerprint) {
        OrderCommand.Operation operation = matchingOperation(message.header().messageType());
        if (owner.batches().hasPendingBatches() && !owner.clusterPipelineAdmission()) {
            return deferMatching(message, clusterTimestamp, clusterPosition, sourceKey, operation, fingerprint);
        }
        return prepareMatching(message, clusterTimestamp, clusterPosition, sourceKey, operation, fingerprint, null);
    }

    /** 把支持的下单、撤单、改单、强平及结算消息映射到既有槽操作。 */
    static OrderCommand.Operation matchingOperation(CoreMessageType messageType) {
        return switch (messageType) {
            case PLACE_ORDER, PLACE_ORDER_BATCH -> OrderCommand.Operation.PLACE;
            case CANCEL_ORDER, CANCEL_ORDER_BATCH -> OrderCommand.Operation.CANCEL;
            case REPLACE_ORDER -> OrderCommand.Operation.REPLACE;
            case AMEND_ORDER, AMEND_ORDER_BATCH -> OrderCommand.Operation.AMEND;
            case EXECUTE_LIQUIDATION -> OrderCommand.Operation.LIQUIDATION;
            case EXECUTE_LIQUIDATION_BATCH -> OrderCommand.Operation.LIQUIDATION_BATCH;
            case SETTLE_INSTRUMENT -> OrderCommand.Operation.SETTLEMENT;
            default -> throw new IllegalArgumentException("not a matching command");
        };
    }

    /** 初次解码或恢复延后输入，先检查生命周期，再建立事实作用域，按操作派发账户准入或检查路由。成功则登记槽、保存收据并释放 Owner 访问权后提交 Matcher，返回待处理状态。 */
    CoreResponse prepareMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                         TradingCoreRuntime.SourceKey sourceKey, OrderCommand.Operation operation,
                                         CommandFingerprint fingerprint, OrderCommand deferredPending) {
        OrderCommand.Decoded decodedCommand;
        try {
            decodedCommand = deferredPending == null
                    ? owner.decodeMatchingCommand(message) : deferredPending.decodedCommand();
        } catch (CoreStateRejectedException rejection) {
            return deferredPending == null
                    ? owner.admissionRejected(CoreResultCode.fromRejectionCode(rejection.code())) : null;
        } catch (ArithmeticException | IllegalArgumentException rejection) {
            return deferredPending == null
                    ? owner.admissionRejected(rejection instanceof ArithmeticException
                            ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND) : null;
        }
        CommandFingerprint effectiveFingerprint = deferredPending == null
                ? fingerprint : deferredPending.fingerprint();
        owner.activateFactContext(message, effectiveFingerprint);
        try {
            rejectLifecycleOverlap(message, operation, decodedCommand);
        } catch (CoreStateRejectedException exception) {
            CoreResponse response = rejectMatching(message, sourceKey, effectiveFingerprint,
                    CoreResultCode.fromRejectionCode(exception.code()), deferredPending);
            return owner.finishFactContext(response);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            CoreResponse response = rejectMatching(message, sourceKey, effectiveFingerprint,
                    exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND, deferredPending);
            return owner.finishFactContext(response);
        }
        long sequence = deferredPending == null
                ? Math.incrementExact(owner.appliedCommandCount()) : deferredPending.sequence();
        List<Long> preMatchingCancellations = List.of();
        com.surprising.aeron.service.order.OrderCommand.PlaceAdmissionEvent placeAdmission = null;
        com.surprising.aeron.service.order.OrderCommand.ReplacementAdmissionEvent replacementAdmission = null;
        owner.resultBuilder.beginCommand();
        try {
            switch (operation) {
                case PLACE -> {
                    var command = decodedCommand.placeOrder();
                    owner.requireOrderIdentityAvailable(message.header().userId(), command);
                    placeAdmission = owner.dispatchPlaceAdmission(message.header().userId(), command,
                            message.header().commandId(), sequence, clusterTimestamp, clusterPosition);
                }
                case CANCEL -> validatePendingCancel(message, decodedCommand);
                case REPLACE -> replacementAdmission = dispatchReplacementAdmission(message, decodedCommand, false, sequence);
                case AMEND -> replacementAdmission = dispatchReplacementAdmission(message, decodedCommand, true, sequence);
                case TRIGGER -> throw new IllegalStateException("trigger matching requires its Lane admission");
                case LIQUIDATION -> validatePendingLiquidation(decodedCommand);
                case LIQUIDATION_BATCH -> validatePendingLiquidationBatch(decodedCommand);
                case SETTLEMENT -> validatePendingSettlement(decodedCommand);
            }
            if (replacementAdmission == null)
                preMatchingCancellations = preMatchingCloseCapacityCancellations(
                        operation, placeAdmission);
        } catch (CoreStateRejectedException exception) {
            CoreResponse response = rejectMatching(message, sourceKey, effectiveFingerprint,
                    CoreResultCode.fromRejectionCode(exception.code()), deferredPending);
            return owner.finishFactContext(response);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            CoreResponse response = rejectMatching(message, sourceKey, effectiveFingerprint,
                    exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND, deferredPending);
            return owner.finishFactContext(response);
        }
        OrderCommand pending = deferredPending == null
                ? owner.pendingMatching().acquire(sequence, operation, message, effectiveFingerprint,
                        preMatchingCancellations, decodedCommand, null)
                : deferredPending.withPreMatchingCancellations(preMatchingCancellations)
                        .withAdmission(null);
        pending.establishCommitFence(clusterTimestamp, clusterPosition);
        if (deferredPending == null) {
            owner.matchingFlow.putPendingMatching(pending);
        } else {
            owner.pendingMatching().put(pending);
            pending.activateDeferredMatching();
        }
        if (placeAdmission != null) pending.placeAdmission(placeAdmission);
        if (replacementAdmission != null) pending.replacementAdmission(replacementAdmission);
        registerPendingLifecycle(pending);
        if (deferredPending == null) {
            owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
        }
        // 推进其他序号的异步续接前先释放 owner 的事实上下文。
        owner.clearFactContext();
        owner.runtimeState().releaseOwnerLaneAccess();
        owner.matchingFlow.submitMatching(pending);
        return message.matchingPendingResponse(com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, sequence);
    }

    /** 先解码输入并分配 Core 序号，再登记为延后项，保存原时间、日志位置及来源。 */
    CoreResponse deferMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                       TradingCoreRuntime.SourceKey sourceKey, OrderCommand.Operation operation,
                                       CommandFingerprint fingerprint) {
        OrderCommand.Decoded decodedCommand;
        try {
            decodedCommand = owner.decodeMatchingCommand(message);
        } catch (CoreStateRejectedException rejection) {
            return owner.admissionRejected(CoreResultCode.fromRejectionCode(rejection.code()));
        } catch (ArithmeticException | IllegalArgumentException rejection) {
            return owner.admissionRejected(rejection instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
        }
        long sequence = owner.nextAppliedSequence();
        OrderCommand pending = newPendingMatching(sequence, operation, message, fingerprint, decodedCommand);
        owner.matchingFlow.putPendingMatching(pending);
        pending.deferMatching(clusterTimestamp, clusterPosition, sourceKey);
        owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
        return message.matchingPendingResponse(com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, sequence);
    }

    /** 新命令交撮合流按当前队列顺序登记拒绝。 */
    private CoreResponse rejectMatching(CoreMessage message, TradingCoreRuntime.SourceKey sourceKey,
                                        CommandFingerprint effectiveFingerprint, CoreResultCode resultCode,
                                        OrderCommand deferredPending) {
        return deferredPending != null
                ? recordRejectedDeferredMatching(deferredPending, resultCode)
                : owner.matchingFlow.recordRejectedMatching(message, sourceKey, effectiveFingerprint, resultCode);
    }

    /** 核对待项可提交，保存其原序号的幂等拒绝结果，移除待项并返回终态。 */
    CoreResponse recordRejectedDeferredMatching(OrderCommand pending, CoreResultCode resultCode) {
        owner.matchingFlow.commitMatchingSequence(pending.sequence());
        owner.resultLedger().storeOwnedResult(pending.command().header().commandIdMsb(), pending.command().header().commandIdLsb(), pending.fingerprint(),
                ResponseStatus.REJECTED, resultCode, pending.sequence(),
                TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        owner.matchingFlow.removePendingMatching(pending.sequence());
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                pending.sequence(), TradingCoreRuntime.EMPTY_RESPONSE_DATA);
    }

    /** 从活动订单路由检查原单存在且归请求用户，记下受影响用户和订单，不在此直接读写账户 Lane 订单。 */
    void validatePendingCancel(CoreMessage message, OrderCommand.Decoded decodedCommand) {
        var command = decodedCommand.cancelOrder();
        var order = owner.activeOrderIndex().activeOrderRoute(command.orderId());
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != message.header().userId()) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        owner.resultBuilder.setSingleChangedUser(message.header().userId());
        owner.resultBuilder.setSingleChangedOrder(command.orderId());
    }

    /** 找原单并核对归属。 */
    com.surprising.aeron.service.order.OrderCommand.ReplacementAdmissionEvent dispatchReplacementAdmission(
            CoreMessage message, OrderCommand.Decoded decodedCommand, boolean amend, long sequence) {
        long originalOrderId = amend ? decodedCommand.amendOrder().originalOrderId()
                : decodedCommand.replaceOrder().originalOrderId();
        var order = owner.activeOrderIndex().activeOrderRoute(originalOrderId);
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != message.header().userId()) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        String instrumentId = amend ? order.instrumentId()
                : decodedCommand.replaceOrder().replacement().instrumentId();
        var decision = context(owner.runtimeState(), owner.identities(), instrumentId, owner.currentClusterTimestamp());
        owner.resultBuilder.setSingleChangedUser(message.header().userId());
        owner.resultBuilder.setSingleChangedOrder(originalOrderId);
        return owner.runtimeState().dispatchReplacementAdmission(sequence, message.header().userId(), originalOrderId,
                amend ? decodedCommand.amendOrder() : null, amend ? null : decodedCommand.replaceOrder(),
                decision,
                owner.matchingAdapter().matcherShardId(instrumentId), owner.identities());
    }

    /** 只有 PLACE 才读取准入的先撤单列表，收据未完成暂给空表。 */
    List<Long> preMatchingCloseCapacityCancellations(OrderCommand.Operation operation,
            com.surprising.aeron.service.order.OrderCommand.PlaceAdmissionEvent placeAdmission) {
        if (operation != OrderCommand.Operation.PLACE) return List.of();
        if (placeAdmission == null) throw new IllegalStateException("place matching requires its Lane admission");
        return placeAdmission.complete() ? placeAdmission.closeCapacityCancellations() : List.of();
    }

    /** 要求强平计划仍有路由，登记该用户及本页活动订单。 */
    void validatePendingLiquidation(OrderCommand.Decoded decodedCommand) {
        var command = decodedCommand.liquidation();
        var route = owner.liquidationIndex().route(command.liquidationId());
        if (route == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        owner.resultBuilder.setSingleChangedUser(route.userId());
        owner.resultBuilder.commandChangedOrderIds = owner.activeOrderIndex().page(route.userId(), route.instrumentId(),
                command.cursorOrderId(), command.maxOrders()).orderIds();
    }

    /** 核对可选风险续扫预算和游标，逐有效强平动作校验用户、品种、正执行价及生命周期范围，拒绝重复用户加品种。 */
    void validatePendingLiquidationBatch(OrderCommand.Decoded decodedCommand) {
        var command = decodedCommand.liquidationBatch();
        if (command.riskScanContinuation() != null) {
            var control = owner.runtimeState().riskScanControl();
            if (!control.enabled() || command.maxRiskScanUsers() > control.scanBatchSize()) {
                throw new CoreStateRejectedException("INVALID_COMMAND",
                        "risk scan continuation exceeds current control");
            }
            var scan = owner.runtimeState().firstRiskIncompleteScan();
            var continuation = command.riskScanContinuation();
            if (scan == null
                    || !owner.identities().instrumentId(scan.symbolId()).equals(continuation.instrumentId())
                    || scan.priceSequence() != continuation.priceSequence()
                    || scan.lastUserId() != continuation.lastUserId()) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "risk scan cursor does not match state");
            }
        }
        batchScopeCount = 0;
        batchChangedUsers.clear();
        batchChangedOrders.clear();
        int remaining = command.maxCancelOrders();
        for (var action : command.actions()) {
            var liquidation = owner.liquidationIndex().route(action.liquidationId());
            if (liquidation == null || !liquidation.executable()) continue;
            if (liquidation.userId() != action.userId() || !liquidation.instrumentId().equals(action.instrumentId())
                    || action.executionPriceTicks() <= 0)
                throw new CoreStateRejectedException("INVALID_COMMAND", "liquidation batch route does not match action");
            String liquidationSymbol = liquidation.instrumentId();
            for (int scopeIndex = 0; scopeIndex < batchScopeCount; scopeIndex++) {
                if (batchScopeUsers[scopeIndex] == liquidation.userId()
                        && batchScopeSymbols[scopeIndex].equals(liquidationSymbol)) {
                    throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                            "liquidation batch contains overlapping scopes");
                }
            }
            if (batchScopeCount == batchScopeUsers.length) {
                int capacity = Math.multiplyExact(batchScopeUsers.length, 2);
                batchScopeUsers = java.util.Arrays.copyOf(batchScopeUsers, capacity);
                batchScopeSymbols = java.util.Arrays.copyOf(batchScopeSymbols, capacity);
            }
            batchScopeUsers[batchScopeCount] = liquidation.userId();
            batchScopeSymbols[batchScopeCount++] = liquidationSymbol;
            ensureLifecycleScopeAvailable(liquidation.userId(), liquidationSymbol,
                    false, action.liquidationId(), true, false);
            batchChangedUsers.add(liquidation.userId());
            if (remaining > 0) {
                ActiveOrderIndex.LifecycleOrderChunk chunk = owner.activeOrderIndex().lifecycleOrders(liquidation.userId(), liquidationSymbol,
                        action.cursorOrderId(), remaining);
                for (var order : chunk.orders()) batchChangedOrders.add(order.orderId());
                remaining -= chunk.orders().size();
            }
        }
        owner.resultBuilder.commandChangedUserIds = batchChangedUsers.toImmutableList();
        owner.resultBuilder.commandChangedOrderIds = batchChangedOrders.toImmutableList();
    }

    /** 已注册品种先检查产品到期规则，再核结算编号、价格和分页游标。 */
    void validatePendingSettlement(OrderCommand.Decoded decodedCommand) {
        var command = decodedCommand.settlement();
        var instrument = owner.runtimeState().instrument(command.instrumentId());
        if (instrument != null) ProductTradingRules.forInstrument(instrument)
                .validateLifecycleSettlement(instrument, command);
        if (instrument != null && !instrument.administrativeSettlement(command)
                && instrument.expiryEpochMillis() > owner.currentClusterTimestamp()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "instrument has not reached expiry");
        }
        var progress = owner.lifecycleProgress(command.instrumentId());
        if (progress == null && (command.cursorUserId() != 0 || command.cursorOrderId() != 0)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor must start at zero");
        }
        if (progress != null && (progress.settlementId() != command.settlementId()
                || progress.settlementPriceTicks() != command.settlementPriceTicks()

                || progress.ordersComplete() != (command.cursorOrderId() == 0)
                || progress.nextCursorOrderId() != command.cursorOrderId()
                || progress.nextCursorUserId() != command.cursorUserId())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor does not match progress");
        }
        ActiveOrderIndex.LifecycleOrderChunk orderChunk = owner.activeOrderIndex().lifecycleOrders(0, command.instrumentId(), command.cursorOrderId(), command.maxOrders());
        boolean orderPhase = progress == null || !progress.ordersComplete();
        if (orderPhase) {
            owner.resultBuilder.addLifecycleChanges(orderChunk.orders());
        } else {
            owner.resultBuilder.commandChangedOrderIds = List.of();
        }
    }

    /** 单笔强平和到期结算按完整生命周期范围检查，其他操作先按现有用户和路由品种检查。 */
    void rejectLifecycleOverlap(CoreMessage message, OrderCommand.Operation operation,
                                        OrderCommand.Decoded decodedCommand) {
        if (operation == OrderCommand.Operation.LIQUIDATION) {
            var command = decodedCommand.liquidation();
            var route = owner.liquidationIndex().route(command.liquidationId());
            ensureLifecycleScopeAvailable(route == null ? 0 : route.userId(),
                    route == null ? "" : route.instrumentId(), false,
                    command.liquidationId(), true, false);
        } else if (operation == OrderCommand.Operation.SETTLEMENT) {
            var command = decodedCommand.settlement();
            ensureLifecycleScopeAvailable(0, command.instrumentId(), true,
                    command.settlementId(), true, false);
        } else ensureLifecycleScopeAvailable(message.header().userId(),
                matchingSymbol(operation, decodedCommand), false, 0, false, true);
    }

    private void ensureLifecycleScopeAvailable(long userId, String instrumentId, boolean settlement,
            long lifecycleId, boolean lifecycle, boolean orderChanging) {
        if (instrumentId.isBlank()) return;
        if (lifecycle) {
            owner.pendingMatching().forEach(pending -> {
                if (pendingLifecycleConflicts(userId, instrumentId, settlement, pending)) {
                    throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                            "matching lifecycle scope is in progress");
                }
            });
        } else if (!pendingLifecycleScopes.isEmpty()) {
            int permanentSymbolId = com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
            for (PendingLifecycleRanges scopes : pendingLifecycleScopes.values())
                if (scopes.conflicts(permanentSymbolId, userId, settlement))
                    throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                            "matching lifecycle scope is in progress");
        }
        Integer candidateSymbolId = owner.identities().findSymbolId(instrumentId);
        if (candidateSymbolId != null && owner.runtimeState().treasury().fundingProgress(candidateSymbolId) != null) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "funding position cut is in progress");
        }
        boolean settlementProgress = candidateSymbolId != null
                && owner.runtimeState().treasury().lifecycleProgress(candidateSymbolId) != null;
        if (settlementProgress
                && orderChanging) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                    "settlement lifecycle is in progress");
        }
        if (lifecycle) {
            boolean liquidationActive = candidateSymbolId != null
                    && hasCommittedLiquidationConflict(
                    settlement ? 0 : userId, candidateSymbolId, lifecycleId);
            boolean settlementContinuation = settlementProgress && settlement
                    && owner.runtimeState().treasury().lifecycleProgress(candidateSymbolId).settlementId()
                    == lifecycleId;
            if (settlementProgress && !settlementContinuation || liquidationActive) {
                throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                        "matching lifecycle scope is in progress");
            }
        }
    }

    /** 从已有活动强平派生索引查找同品种、指定用户或全用户的可执行计划，排除当前生命周期编号，不跨线程扫描账户状态。 */
    private boolean hasCommittedLiquidationConflict(long userId, int symbolId, long excludedId) {
        for (long id : owner.liquidationIndex().activeIds()) {
            if (id == excludedId) continue;
            var liquidation = owner.liquidationIndex().route(id);
            if (liquidation != null && (userId == 0 || liquidation.userId() == userId)
                    && liquidation.instrumentId().equals(owner.identities().instrumentId(symbolId))
                    && liquidation.executable()) return true;
        }
        return false;
    }

    /** 生命周期候选与在途命令比较。 */
    boolean pendingLifecycleConflicts(long userId, String instrumentId, boolean settlement, OrderCommand pending) {
        if (instrumentId.isBlank()) return false;
        if (pending.operation() == OrderCommand.Operation.LIQUIDATION_BATCH) {
            for (var action : pending.decodedCommand().liquidationBatch().actions())
                if (sameScope(userId, instrumentId, settlement,
                        action.userId(), action.instrumentId(), false)) return true;
            return false;
        }
        if (pending.operation() == OrderCommand.Operation.SETTLEMENT)
            return sameScope(userId, instrumentId, settlement, 0,
                    pending.decodedCommand().settlement().instrumentId(), true);
        if (pending.operation() == OrderCommand.Operation.LIQUIDATION) {
            var route = owner.liquidationIndex().route(pending.decodedCommand().liquidation().liquidationId());
            return route != null && sameScope(userId, instrumentId, settlement,
                    route.userId(), route.instrumentId(), false);
        }
        return sameScope(userId, instrumentId, settlement,
                pending.command().header().userId(), matchingSymbol(pending.operation(), pending.decodedCommand()), false);
    }

    private static boolean sameScope(long leftUser, String leftSymbol, boolean leftSettlement,
            long rightUser, String rightSymbol, boolean rightSettlement) {
        return leftSymbol.equals(rightSymbol) && (leftSettlement || rightSettlement || leftUser == rightUser);
    }

    /** 只把强平和交割的原语范围登记到在途索引。 */
    void registerPendingLifecycle(OrderCommand pending) {
        PendingLifecycleRanges ranges;
        switch (pending.operation()) {
            case LIQUIDATION -> {
                var route = owner.liquidationIndex().route(pending.decodedCommand().liquidation().liquidationId());
                if (route == null) return;
                ranges = borrowLifecycleRanges(1);
                ranges.add(route.userId(), route.instrumentId(), false);
            }
            case SETTLEMENT -> {
                ranges = borrowLifecycleRanges(1);
                ranges.add(0, pending.decodedCommand().settlement().instrumentId(), true);
            }
            case LIQUIDATION_BATCH -> {
                var actions = pending.decodedCommand().liquidationBatch().actions();
                ranges = borrowLifecycleRanges(actions.size());
                for (var action : actions) ranges.add(action.userId(), action.instrumentId(), false);
            }
            default -> { return; }
        }
        if (ranges.count != 0) pendingLifecycleScopes.put(pending.sequence(), ranges);
    }

    private PendingLifecycleRanges borrowLifecycleRanges(int capacity) {
        PendingLifecycleRanges ranges = lifecycleRangePool.pollFirst();
        if (ranges == null) return new PendingLifecycleRanges(capacity);
        ranges.ensureCapacity(capacity);
        return ranges;
    }

    void releasePendingLifecycle(long sequence) {
        PendingLifecycleRanges ranges = pendingLifecycleScopes.remove(sequence);
        if (ranges == null) return;
        ranges.clear();
        if (lifecycleRangePool.size() < 128) lifecycleRangePool.addFirst(ranges);
    }

    /** 在途生命周期范围只存判冲突需要的原语列。 */
    static final class PendingLifecycleRanges {
        private int[] symbols;
        private long[] users;
        private boolean[] settlements;
        private int count;

        PendingLifecycleRanges(int capacity) {
            symbols = new int[capacity];
            users = new long[capacity];
            settlements = new boolean[capacity];
        }

        void ensureCapacity(int capacity) {
            if (symbols.length >= capacity) return;
            symbols = java.util.Arrays.copyOf(symbols, capacity);
            users = java.util.Arrays.copyOf(users, capacity);
            settlements = java.util.Arrays.copyOf(settlements, capacity);
        }

        void clear() { count = 0; }

        void add(long userId, String instrumentId, boolean settlement) {
            if (instrumentId.isBlank()) return;
            int symbolId = com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
            int index = count++;
            symbols[index] = symbolId;
            users[index] = userId;
            settlements[index] = settlement;
        }

        boolean conflicts(int symbolId, long userId, boolean settlement) {
            for (int index = 0; index < count; index++)
                if (symbols[index] == symbolId
                        && (settlement || settlements[index] || users[index] == userId)) return true;
            return false;
        }
    }

    /** 下单取解码合约，撤改单取原活动订单路由，触发子单取源触发路由并要求品种仍存在。 */
    String matchingSymbol(OrderCommand.Operation operation,
                                  OrderCommand.Decoded decodedCommand) {
        return switch (operation) {
            case PLACE -> decodedCommand.placeOrder().instrumentId();
            case CANCEL -> {
                var command = decodedCommand.cancelOrder();
                var order = owner.activeOrderIndex().activeOrderRoute(command.orderId());
                yield order == null ? "" : order.instrumentId();
            }
            case REPLACE, AMEND -> {
                long orderId = operation == OrderCommand.Operation.REPLACE
                        ? decodedCommand.replaceOrder().originalOrderId()
                        : decodedCommand.amendOrder().originalOrderId();
                var order = owner.activeOrderIndex().activeOrderRoute(orderId);
                yield order == null ? "" : order.instrumentId();
            }
            case TRIGGER -> {
                long[] execute = decodedCommand.trigger();
                var trigger = owner.triggerOrderIndex().route(execute[0]);
                if (trigger == null) yield "";
                var instrument = owner.runtimeState().instrument(trigger.instrumentId());
                if (instrument == null)
                    throw new com.surprising.aeron.exception.CoreStateRejectedException(
                            "INSTRUMENT_NOT_FOUND", "trigger instrument state is missing");
                yield instrument.instrumentId();
            }
            case LIQUIDATION, SETTLEMENT -> "";
            case LIQUIDATION_BATCH -> {
                var batch = decodedCommand.liquidationBatch();
                yield batch.actions().isEmpty() ? "" : batch.actions().getFirst().instrumentId();
            }
        };
    }

    /** 结算直接取合约，批次强平取第一项，单笔强平取现有计划路由。 */
    String pendingLifecycleSymbol(OrderCommand pending) {
        if (pending.operation() == OrderCommand.Operation.SETTLEMENT) {
            return pending.decodedCommand().settlement().instrumentId();
        }
        if (pending.operation() == OrderCommand.Operation.LIQUIDATION_BATCH) {
            var batch = pending.decodedCommand().liquidationBatch();
            return batch.actions().isEmpty() ? "" : batch.actions().getFirst().instrumentId();
        }
        var liquidation = owner.liquidationIndex().route(
                pending.decodedCommand().liquidation().liquidationId());
        return liquidation == null ? "" : liquidation.instrumentId();
    }

    /** 返回当前待正式登记的触发子单列表长度。 */
    public int queuedMatchingCount() { return queuedMatching.size(); }
    /** 新建空生命周期范围索引。会替换原索引，只能在初始化或恢复约定边界调用。 */
    public void initializeLifecycleScopes() {
        pendingLifecycleScopes = new LongObjectHashMap<>();
        lifecycleRangePool.clear();
    }
    /** 清空已初始化的范围索引，保留容器供后续命令复用。 */
    public void clearLifecycleScopes() {
        for (PendingLifecycleRanges ranges : pendingLifecycleScopes.values()) {
            ranges.clear();
            if (lifecycleRangePool.size() < 128) lifecycleRangePool.addFirst(ranges);
        }
        pendingLifecycleScopes.clear();
    }

    /** 百万分比的分母，OI 比例限额和杠杆相关计算共用此比例单位。 */
    private static final long PPM = 1_000_000L;

    /** 普通订单准入的便捷入口，不排除已有订单，继续取得身份并计算所需预留。 */
    public static long requiredReservation(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, long userId,
            OrderCommand.ResolvedPlaceOrder order, long openInterestSteps) {
        return requiredReservation(runtime, identities, userId, order, openInterestSteps, 0);
    }

    /** 定位用户所属 Lane，准备客户、币对、持仓及账本身份。 */
    public static long requiredReservation(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, long userId,
            OrderCommand.ResolvedPlaceOrder order, long openInterestSteps,
            long excludedOrderId) {
        if (runtime == null || identities == null || order == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime order admission input");
        }
        AccountPartition lane = runtime.accountLaneForUser(userId);
        int symbolId = identities.symbolId(order.instrumentId());
        long clientKey = identities.findClientKeyValue(userId, order.clientOrderId());
        long positionKey = identities.findPositionKeyValue(userId, order.instrument(), order.positionSide());
        var settlement = runtime.treasury().settlementStatus(symbolId);
        Order excluded = excludedOrderId == 0 ? null : lane.order(excludedOrderId);
        return requiredReservation(runtime, lane, userId, order, openInterestSteps,
                excluded, clientKey, positionKey,
                settlement.lifecycleSettled(), settlement.fundingInProgress(), null);
    }

    /** 直接使用已准备的 primitive 身份值和生命周期标记执行普通订单准入，不额外构造身份记录。 */
    public static long requiredReservationPrepared(
            TradingAccountRuntime runtime, AccountPartition lane, long userId, OrderCommand.ResolvedPlaceOrder order,
            long openInterestSteps,
            long clientKey, long positionKey,
            boolean lifecycleSettled, boolean fundingInProgress) {
        if (runtime == null || order == null || userId <= 0) {
            throw new IllegalArgumentException("invalid primitive prepared runtime order admission input");
        }
        return requiredReservation(runtime, lane, userId, order, openInterestSteps,
                null, clientKey, positionKey, lifecycleSettled, fundingInProgress, null);
    }

    /** 改单准入直接接收已读取的原单引用，排除原单占用并校验新单预留，消除多重哈希查找。 */
    public static long requiredReplacementReservationPrepared(
            TradingAccountRuntime runtime, AccountPartition lane, long userId, OrderCommand.ResolvedPlaceOrder order,
            long openInterestSteps, Order original,
            long clientKey, long positionKey, boolean lifecycleSettled, boolean fundingInProgress) {
        return requiredReservation(runtime, lane, userId, order, openInterestSteps,
                original, clientKey, positionKey, lifecycleSettled, fundingInProgress, null);
    }

    /** 兼容原单编号入参的改单准入，在当前所属 Lane 一次性查找原单后直达核心检查。 */
    static long requiredReplacementReservationPrepared(
            TradingAccountRuntime runtime, AccountPartition lane, long userId, OrderCommand.ResolvedPlaceOrder order,
            long openInterestSteps, long excludedOrderId,
            long clientKey, long positionKey, boolean lifecycleSettled, boolean fundingInProgress) {
        Order original = excludedOrderId == 0 ? null : lane.order(excludedOrderId);
        return requiredReservation(runtime, lane, userId, order, openInterestSteps,
                original, clientKey, positionKey, lifecycleSettled, fundingInProgress, null);
    }

    /** 预检只读取显式所属 Lane 和 Owner 捕获的币对配置。 */
    public static long requiredReservationForQuery(TradingAccountRuntime runtime, AccountPartition lane, long userId,
            OrderCommand.ResolvedPlaceOrder order, long openInterestSteps, int symbolId, long clientKey, long positionKey,
            com.surprising.aeron.service.treasury.TreasuryRuntime.SettlementStatus settlementStatus) {
        if (lane == null || runtime.topology().accountLaneId(userId) != lane.laneId()
                || order == null || settlementStatus == null)
            throw new IllegalStateException("preflight query requires its owning Lane scope");
        if (symbolId != order.symbolId()) throw new IllegalArgumentException("preflight symbol route mismatch");
        return requiredReservation(runtime, lane, userId, order, openInterestSteps,
                null, clientKey,
                positionKey, settlementStatus.lifecycleSettled(), settlementStatus.fundingInProgress(), order.instrument());
    }

    private static long requiredReservation(
            TradingAccountRuntime runtime, AccountPartition lane, long userId, OrderCommand.ResolvedPlaceOrder order,
            long openInterestSteps, Order excluded,
            long clientKey, long positionKey,
            boolean lifecycleSettled, boolean fundingInProgress, CoreInstrument queryInstrument) {
        lane.assertOwner();
        lane.requireAlgoChildAdmission(userId, order);
        long excludedOrderId = excluded == null ? 0 : excluded.orderId();
        if (lane.order(order.orderId()) != null && order.orderId() != excludedOrderId) {
            throw rejected("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        var clientOrders = lane.userClientOrders(userId);
        long clientOrderId = clientKey == 0 || clientOrders == null ? 0 : clientOrders.get(clientKey);
        if (clientOrderId != 0 && clientOrderId != excludedOrderId) {
            throw rejected("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        CoreInstrument instrument = queryInstrument == null ? runtime.instrument(lane, order.instrumentId()) : queryInstrument;
        if (instrument == null || instrument != order.instrument()) {
            throw rejected("INSTRUMENT_ORDER_MISMATCH", "order instrument differs from Runtime");
        }
        if (lifecycleSettled) {
            throw rejected("INSTRUMENT_SETTLED", "instrument is already settled");
        }
        if (fundingInProgress) {
            throw rejected("LIFECYCLE_IN_PROGRESS", "funding position cut is in progress");
        }
        validateReservation(instrument.contractType().productLine().isDerivative(), instrument, order);
        UserAccount user = lane.user(userId);
        CorePositionMode positionMode = user == null ? CorePositionMode.ONE_WAY : user.positionMode();
        Position position = positionKey == 0 ? null : lane.position(positionKey);
        // 本次改单排除的原单，必须存在且用户及币对一致。
        if (excluded != null && (excluded.userId() != userId || excluded.symbolId() != order.symbolId())) {
            throw rejected("ORDER_NOT_FOUND", "excluded replacement order is invalid");
        }
        CoreMarginMode conflictingMode = order.marginMode() == CoreMarginMode.CROSS
                ? CoreMarginMode.ISOLATED : CoreMarginMode.CROSS;
        AdmissionSummary admissionSummary = lane.admissionSummary(order.symbolId(), userId,
                order.positionSide(), order.side(), conflictingMode);
        validatePositionIdentity(
                positionMode, position, order, admissionSummary, conflictingMode, excluded);
        validateReduceOnly(instrument.contractType().productLine().isDerivative(), position, order,
                admissionSummary, userId, excluded);
        long leverage = effectiveLeverage(lane, instrument, order, userId);
        validateRiskLimits(instrument, position, order, admissionSummary, userId,
                openInterestSteps, excluded, leverage);
        return ProductTradingRules.forProductLine(instrument.contractType().productLine())
                .reservationUnits(instrument, position, order, leverage, admissionSummary.pendingQuantity());
    }

    private static void validateReservation(
            boolean derivative, CoreInstrument instrument, OrderCommand.ResolvedPlaceOrder order) {
        String asset = order.reservationAsset();
        if (asset == null) {
            throw rejected("INVALID_RESERVATION_ASSET", "reservation asset is required");
        }
        if (derivative) {
            if (order.reservationKind() != ReservationKind.DERIVATIVE_MARGIN) {
                throw rejected("INVALID_RESERVATION_KIND", "derivative order must reserve margin");
            }
            if (!asset.equals(instrument.settleAsset())) {
                throw rejected("INVALID_DERIVATIVE_RESERVATION_ASSET", "invalid derivative reservation asset");
            }
        } else {
            if (order.reservationKind() != ReservationKind.SPOT_ASSET) {
                throw rejected("INVALID_RESERVATION_KIND", "spot order must reserve an asset");
            }
            String expected = order.side() == CoreOrderSide.BUY
                    ? instrument.quoteAsset() : instrument.baseAsset();
            if (!asset.equals(expected)) {
                throw rejected("INVALID_SPOT_RESERVATION_ASSET", "invalid spot reservation asset");
            }
        }
        if (order.matchingPriceTicks() <= 0) {
            throw rejected("INVALID_ORDER_PRICE", "matching price must be positive");
        }
    }

    private static void validatePositionIdentity(
            CorePositionMode mode, Position position, OrderCommand.ResolvedPlaceOrder order,
            AdmissionSummary admissionSummary, CoreMarginMode conflictingMode, Order excluded) {
        if ((mode == CorePositionMode.ONE_WAY && order.positionSide().hedgeSide())
                || (mode == CorePositionMode.HEDGE && !order.positionSide().hedgeSide())) {
            throw rejected("POSITION_MODE_MISMATCH", "position side differs from user position mode");
        }
        if (order.marginMode() == CoreMarginMode.ISOLATED
                && order.reservationKind() == ReservationKind.SPOT_ASSET) {
            throw rejected("POSITION_MARGIN_ADJUSTMENT_INVALID", "spot order cannot use isolated margin");
        }
        boolean positionConflict = position != null && position.signedQuantitySteps() != 0
                && position.marginMode() != order.marginMode();
        int conflictingOrders = admissionSummary.marginModeCount();
        if (excluded != null && excluded.status() == CoreOrderStatus.OPEN
                && excluded.positionSide() == order.positionSide()
                && excluded.marginMode() == conflictingMode) {
            conflictingOrders--;
        }
        if (positionConflict || conflictingOrders > 0) {
            throw rejected("POSITION_MARGIN_ADJUSTMENT_INVALID", "margin mode switch requires an empty position");
        }
        if ((order.positionSide() == CorePositionSide.LONG
                && order.reduceOnly() == (order.side() == CoreOrderSide.BUY))
                || (order.positionSide() == CorePositionSide.SHORT
                && order.reduceOnly() == (order.side() == CoreOrderSide.SELL))) {
            throw rejected("POSITION_MODE_MISMATCH", "hedge side and direction are inconsistent");
        }
    }

    /** 只减仓仅支持衍生品且必须逆向减少已有非零持仓。 */
    private static void validateReduceOnly(
            boolean derivative, Position position, OrderCommand.ResolvedPlaceOrder order,
            AdmissionSummary admissionSummary, long userId, Order excluded) {
        if (!order.reduceOnly()) return;
        if (!derivative) throw rejected("REDUCE_ONLY_UNSUPPORTED", "spot order cannot be reduce-only");
        if (position == null || position.signedQuantitySteps() == 0
                || (position.signedQuantitySteps() > 0) == (order.side() == CoreOrderSide.BUY)) {
            throw rejected("REDUCE_ONLY_REQUIRES_POSITION_STATE", "reduce-only order must close a position");
        }
        long positionSteps = Math.absExact(position.signedQuantitySteps());
        long committedOrders = admissionSummary.reduceOnlyQuantity();
        if (excluded != null && excluded.status() == CoreOrderStatus.OPEN && excluded.reduceOnly()
                && excluded.userId() == userId && excluded.side() == order.side()) {
            committedOrders = Math.subtractExact(committedOrders, excluded.remainingQuantitySteps());
        }
        long committed = Math.min(positionSteps, committedOrders);
        if (order.quantitySteps() > Math.subtractExact(positionSteps, committed)) {
            throw rejected("REDUCE_ONLY_CAPACITY_EXCEEDED", "reduce-only quantity exceeds position capacity");
        }
    }

    private static void validateRiskLimits(CoreInstrument instrument, Position position,
            OrderCommand.ResolvedPlaceOrder order, AdmissionSummary admissionSummary, long userId, long openInterestSteps,
            Order excluded, long leverage) {
        if (!instrument.contractType().productLine().isDerivative() || order.reduceOnly()) return;
        long current = position == null ? 0 : position.signedQuantitySteps();
        long pending = admissionSummary.pendingQuantity();
        if (excluded != null && excluded.status() == CoreOrderStatus.OPEN && !excluded.reduceOnly()
                && excluded.userId() == userId && excluded.positionSide() == order.positionSide()
                && excluded.side() == order.side()) {
            pending = Math.subtractExact(pending, excluded.remainingQuantitySteps());
        }
        long totalOrders = Math.addExact(pending, order.quantitySteps());
        long signedOrders = order.side() == CoreOrderSide.BUY ? totalOrders : Math.negateExact(totalOrders);
        long projectedSteps = Math.absExact(Math.addExact(current, signedOrders));
        long riskPriceTicks = instrument.contractType().isOption()
                ? order.indexPriceTicks() : order.markPriceTicks();
        long projectedNotional = CoreContractMath.riskNotionalUnits(
                instrument, projectedSteps, riskPriceTicks);
        if (projectedNotional > instrument.maxPositionNotionalUnits()) {
            throw rejected("POSITION_NOTIONAL_LIMIT_EXCEEDED", "projected position exceeds instrument limit");
        }
        long scaledLimit = Math.min(instrument.userOpenInterestLimitFloorUnits(), instrument.maxPositionNotionalUnits());
        if (instrument.usesOpenInterestForUserLimit()) {
            long openInterestNotional = openInterestSteps == 0 ? 0
                    : CoreContractMath.riskNotionalUnits(instrument, openInterestSteps, riskPriceTicks);
            scaledLimit = CoreArithmetic.scaledFloorCapped(
                    openInterestNotional, instrument.userOpenInterestLimitRatePpm(), PPM,
                    instrument.userOpenInterestLimitFloorUnits(), instrument.maxPositionNotionalUnits());
        }
        if (projectedNotional > scaledLimit) {
            throw rejected("OPEN_INTEREST_LIMIT_EXCEEDED", "projected position exceeds open-interest limit");
        }
        var bracket = CoreContractMath.riskBracket(instrument, projectedNotional);
        if (!instrument.contractType().isOption() && (leverage > bracket.maxLeveragePpm()
                || CoreContractMath.initialMarginRateFromLeverage(leverage) < bracket.initialMarginRatePpm())) {
            throw rejected("LEVERAGE_EXCEEDS_RISK_BRACKET", "configured leverage exceeds risk bracket");
        }
    }

    private static long effectiveLeverage(
            AccountPartition lane, CoreInstrument instrument,
            OrderCommand.ResolvedPlaceOrder order, long userId) {
        if (!instrument.contractType().productLine().isDerivative() || instrument.contractType().isOption()) return 0;
        if (lane.leverageValuesEmpty()) return instrument.maxLeveragePpm();
        var lookup = lane.leverageLookup();
        lookup.set(userId, instrument.instrumentId(), order.marginMode());
        Long configured;
        try { configured = lane.leverage(lookup); }
        finally { lookup.clear(); }
        return configured == null ? instrument.maxLeveragePpm() : configured;
    }

    private static CoreStateRejectedException rejected(String code, String message) {
        return new CoreStateRejectedException(code, message);
    }

    /** 一次挂单准入检查的可复用汇总结果，只存数量和冲突条数。 */
    public static final class AdmissionSummary {
        @lombok.experimental.Accessors(fluent = true, chain = false)
        @lombok.Getter
        private long pendingQuantity;
        @lombok.experimental.Accessors(fluent = true, chain = false)
        @lombok.Getter
        private long reduceOnlyQuantity;
        @lombok.experimental.Accessors(fluent = true, chain = false)
        @lombok.Getter
        private int marginModeCount;

        public AdmissionSummary set(long pendingQuantity, long reduceOnlyQuantity, int marginModeCount) {
            this.pendingQuantity = pendingQuantity;
            this.reduceOnlyQuantity = reduceOnlyQuantity;
            this.marginModeCount = marginModeCount;
            return this;
        }
    }

    public static OrderCommand.ResolvedPlaceOrder resolve(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                             long userId, PlaceOrderCommand intent, long clusterTimestamp) {
        if (runtime == null || identities == null || intent == null || userId <= 0 || clusterTimestamp <= 0) {
            throw new IllegalArgumentException("invalid order decision input");
        }
        runtime.assertOwner();
        CoreInstrument instrument = requireInstrument(runtime.instrument(intent.instrumentId()));
        int symbolId = requireSymbolId(identities, instrument);
        return resolveValues(null, instrument, symbolId, runtime.markPrice(symbolId),
                runtime.feePolicyBook(), userId,
                clusterTimestamp, runtime.treasury().lifecycleSettlement(symbolId) != 0, intent, false);
    }

    public static void prepare(OrderCommand.PlaceAdmissionEvent target, TradingAccountRuntime runtime,
                               RuntimeIdentityRegistry identities, long userId, PlaceOrderCommand intent, long clusterTimestamp) {
        if (target == null || runtime == null || identities == null || intent == null
                || userId <= 0 || clusterTimestamp <= 0)
            throw new IllegalArgumentException("invalid order decision input");
        runtime.assertOwner();
        CoreInstrument instrument = requireInstrument(runtime.instrument(intent.instrumentId()));
        int symbolId = requireSymbolId(identities, instrument);
        instrument.requireOrderEnabled(intent);
        if (instrument.expiryEpochMillis() > 0 && clusterTimestamp >= instrument.expiryEpochMillis()) {
            boolean lifecycleSettled = runtime.treasury().lifecycleSettlement(symbolId) != 0;
            throw new CoreStateRejectedException(lifecycleSettled ? "INSTRUMENT_SETTLED" : "INVALID_COMMAND", "expired instrument cannot accept new orders");
        }
        target.prepareDecision(intent, instrument, symbolId, runtime.markPrice(symbolId), runtime.feePolicyBook(), clusterTimestamp);
    }

    public static void resolvePrepared(OrderCommand.PlaceAdmissionEvent target, MarkPrice mark,
                                       FeePolicyBook policies, long userId, long clusterTimestamp, boolean lifecycleSettled) {
        resolveValues(target, target.instrument(), target.symbolId(), mark, policies, userId,
                clusterTimestamp, lifecycleSettled, target.intent(), true);
    }

    public record Context(CoreInstrument instrument, int symbolId, MarkPrice mark,
                          FeePolicyBook policies, long clusterTimestamp,
                          com.surprising.aeron.service.treasury.TreasuryRuntime.SettlementStatus settlementStatus) {
        public boolean lifecycleSettled() { return settlementStatus.lifecycleSettled(); }
        public boolean fundingInProgress() { return settlementStatus.fundingInProgress(); }
    }

    public static <T extends OrderCommand.ResolvedPlaceOrder> T resolveInto(T target, Context context, long userId, PlaceOrderCommand intent) {
        if (target == null || context == null || intent == null) throw new IllegalArgumentException("invalid order decision input");
        resolveValues(target, context.instrument(), context.symbolId(), context.mark(), context.policies(), userId,
                context.clusterTimestamp(), context.lifecycleSettled(), intent, false);
        return target;
    }

    public static OrderCommand.ResolvedPlaceOrder resolve(Context context, long userId, PlaceOrderCommand intent) {
        if (context == null || intent == null) throw new IllegalArgumentException("invalid order decision input");
        return resolveValues(null, context.instrument(), context.symbolId(), context.mark(), context.policies(), userId,
                context.clusterTimestamp(), context.lifecycleSettled(), intent, false);
    }

    public static Context context(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, String instrumentId, long clusterTimestamp) {
        return context(runtime, identities, com.surprising.product.api.utils.InstrumentIds.parse(instrumentId), clusterTimestamp);
    }

    public static Context context(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, int instrumentId, long clusterTimestamp) {
        runtime.assertOwner();
        CoreInstrument instrument = requireInstrument(runtime.instrument(instrumentId));
        int symbolId = requireSymbolId(identities, instrument);
        return new Context(instrument, symbolId, runtime.markPrice(symbolId),
                runtime.feePolicyBook(), clusterTimestamp, runtime.treasury().settlementStatus(symbolId));
    }

    private static CoreInstrument requireInstrument(CoreInstrument instrument) {
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        return instrument;
    }

    private static int requireSymbolId(RuntimeIdentityRegistry identities, CoreInstrument instrument) {
        int symbolId = identities.findSymbolIdValue(instrument.instrumentIdValue());
        if (symbolId < 0) throw new IllegalStateException("instrument instrumentId identity is missing");
        return symbolId;
    }

    public static Context queryContext(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, String instrumentId, long clusterTimestamp) {
        Context source = context(runtime, identities, instrumentId, clusterTimestamp);
        CoreInstrument instrument = source.instrument().captureForQuery();
        MarkPrice mark = source.mark();
        MarkPrice captured = mark == null || mark.instrument() != source.instrument() ? mark
                : new MarkPrice(mark.symbolId(), instrument,
                mark.markPriceTicks(), mark.indexPriceTicks(), mark.forwardPriceTicks(), mark.priceSequence(),
                mark.generatedAtEpochMillis(), mark.lastPriceTicks());
        return new Context(instrument, source.symbolId(), captured, source.policies(), source.clusterTimestamp(),
                source.settlementStatus());
    }

    private static OrderCommand.ResolvedPlaceOrder resolveValues(OrderCommand.ResolvedPlaceOrder target,
                                                    CoreInstrument instrument, int symbolId,
                                                    MarkPrice mark, FeePolicyBook policies, long userId,
                                                    long clusterTimestamp, boolean lifecycleSettled,
                                                    PlaceOrderCommand intent, boolean preparedRoute) {
        if (policies == null || userId <= 0) throw new IllegalArgumentException("invalid fee decision input");
        if (!instrument.instrumentId().equals(intent.instrumentId())) throw new IllegalArgumentException("decision context instrumentId mismatch");
        instrument.requireOrderEnabled(intent);
        if (instrument.expiryEpochMillis() > 0 && clusterTimestamp >= instrument.expiryEpochMillis())
            throw new CoreStateRejectedException(lifecycleSettled ? "INSTRUMENT_SETTLED" : "INVALID_COMMAND", "expired instrument cannot accept new orders");
        boolean spot = instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT;
        if (spot && intent.orderType() == CoreOrderType.MARKET && intent.limitPriceTicks() <= 0)
            throw new CoreStateRejectedException("INVALID_COMMAND", "spot market order requires a protection bound");
        if (!spot && intent.orderType() == CoreOrderType.MARKET && intent.limitPriceTicks() != 0)
            throw new CoreStateRejectedException("INVALID_COMMAND", "derivative market order price must be zero");
        if (!spot) {
            requireFreshMark(mark, instrument, clusterTimestamp);
            if (intent.orderType() == CoreOrderType.LIMIT && instrument.orderProtection().limitPriceProtectionEnabled()
                    && clusterTimestamp - mark.generatedAtEpochMillis() > instrument.orderProtection().limitPriceMaxMarkAgeMs())
                throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price is outside the limit protection freshness bound");
        }
        long markPriceTicks = spot ? intent.limitPriceTicks() : mark.markPriceTicks();
        long indexPriceTicks = spot ? 0 : mark.indexPriceTicks();
        long forwardPriceTicks = spot ? 0 : mark.forwardPriceTicks();
        long matchingPriceTicks = spot || intent.orderType() == CoreOrderType.LIMIT
                ? intent.limitPriceTicks() : protectedPrice(intent.side(), markPriceTicks, instrument.orderProtection().marketMaxSlippagePpm());
        requireLimitPriceBand(intent, instrument, markPriceTicks);
        long reservationPriceTicks = reservationPrice(intent, instrument, markPriceTicks, matchingPriceTicks);
        ReservationKind reservationKind = instrument.contractType()
                == com.surprising.instrument.api.model.ContractType.SPOT
                ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
        String reservationAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN
                ? instrument.settleAsset()
                : intent.side() == CoreOrderSide.BUY ? instrument.quoteAsset() : instrument.baseAsset();
        var fee = policies.select(userId, instrument.instrumentId(), clusterTimestamp);
        long makerFee = fee == null ? instrument.makerFeeRatePpm() : fee.makerFeeRatePpm();
        long takerFee = fee == null ? instrument.takerFeeRatePpm() : fee.takerFeeRatePpm();
        if (target == null) {
            return new OrderCommand.ResolvedPlaceOrder(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks,
                    markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                    makerFee, takerFee);
        }
        if (!preparedRoute) target.clearResolvedOrder();
        target.initializeResolvedOrder(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks,
                markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                makerFee, takerFee);
        return target;
    }

    private static void requireLimitPriceBand(PlaceOrderCommand intent, CoreInstrument instrument, long markPriceTicks) {
        if (instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                || intent.orderType() != CoreOrderType.LIMIT || !instrument.orderProtection().limitPriceProtectionEnabled()) return;
        long boundary = protectedPrice(intent.side(), markPriceTicks, instrument.orderProtection().limitPriceBandPpm());
        if (intent.side() == CoreOrderSide.BUY ? intent.limitPriceTicks() > boundary : intent.limitPriceTicks() < boundary)
            throw new CoreStateRejectedException("INVALID_COMMAND", "limit price exceeds configured mark price band");
    }

    private static void requireFreshMark(MarkPrice mark, CoreInstrument instrument,
                                         long clusterTimestamp) {
        if (mark == null || mark.instrument() != instrument) {
            throw new CoreStateRejectedException("MARK_PRICE_MISSING", "current instrument mark price is required");
        }
        long age = Math.subtractExact(clusterTimestamp, mark.generatedAtEpochMillis());
        if (age < 0 || age > instrument.orderProtection().marketMaxMarkAgeMs()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price is outside the Core freshness bound");
        }
    }

    private static long protectedPrice(CoreOrderSide side, long markPriceTicks, long slippagePpm) {
        long factor = side == CoreOrderSide.BUY
                ? 1_000_000L + slippagePpm
                : 1_000_000L - slippagePpm;
        return Math.max(1, scalePpm(markPriceTicks, factor, side == CoreOrderSide.BUY));
    }

    private static long reservationPrice(PlaceOrderCommand intent, CoreInstrument instrument,
                                         long markPriceTicks, long matchingPriceTicks) {
        if (instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT) {
            return matchingPriceTicks;
        }
        if (instrument.contractType().isOption()) {
            return matchingPriceTicks;
        }
        long slippagePpm = instrument.orderProtection().marketMaxSlippagePpm();
        long lower = boundedMark(markPriceTicks, 1_000_000L - slippagePpm, false);
        long upper = boundedMark(markPriceTicks, 1_000_000L + slippagePpm, true);
        if (intent.orderType() == CoreOrderType.MARKET) {
            return instrument.contractType().isInverse() ? lower : upper;
        }
        if (instrument.contractType().isInverse() && intent.side() == CoreOrderSide.BUY) {
            return Math.min(intent.limitPriceTicks(), lower);
        }
        if (instrument.contractType().isLinear() && intent.side() == CoreOrderSide.SELL) {
            return Math.max(intent.limitPriceTicks(), upper);
        }
        return intent.limitPriceTicks();
    }

    private static long boundedMark(long markPriceTicks, long factor, boolean ceiling) {
        return Math.max(1, scalePpm(markPriceTicks, factor, ceiling));
    }

    public static long scalePpm(long value, long factor, boolean ceiling) {
        long whole = Math.multiplyExact(value / PPM, factor);
        long remainderProduct = Math.multiplyExact(value % PPM, factor);
        long scaled = Math.addExact(whole, remainderProduct / PPM);
        return ceiling && remainderProduct % PPM != 0 ? Math.incrementExact(scaled) : scaled;
    }

}
