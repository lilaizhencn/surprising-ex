package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.business.ProductTradingRulesRegistry;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import com.surprising.aeron.service.command.ImmutableLongArrayList;
import com.surprising.aeron.service.command.support.PrimitiveLongChangeSet;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/** 撮合命令准入：校验订单与生命周期依赖，登记延后命令，不提交未完成结算。 */
final class MatchingCommandAdmission {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingCoreRuntime owner;

    MatchingCommandAdmission(TradingCoreRuntime owner) { this.owner = owner; }

    /** 在途清算或交割的资源范围，防止真实资金依赖交错。 */
    LongObjectHashMap<List<LifecycleScope>> pendingLifecycleScopes;

    /** 由触发等业务产生、等待正式登记的撮合命令。 */
    final List<QueuedTriggerMatching> queuedMatching = new ArrayList<>();

    /** 批量清算校验的复用 scope scratch；Owner 独占，避免每项拼接字符串和 HashSet 节点。 */
    private long[] batchScopeUsers = new long[8];
    private String[] batchScopeSymbols = new String[8];
    private int batchScopeCount;
    private final PrimitiveLongChangeSet batchChangedUsers = new PrimitiveLongChangeSet();
    private final PrimitiveLongChangeSet batchChangedOrders = new PrimitiveLongChangeSet();

    /** 在释放 Lane 访问权前绑定已冻结的子订单；仅存活到登记 CommandSlot，不建立第二份订单索引。 */
    record QueuedTriggerMatching(CoreMessage command, OrderRuntime order,
            com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger) { }

    void appendQueuedMatching(long clusterTimestamp, long clusterPosition) {
        if (queuedMatching.isEmpty()) return;
        for (QueuedTriggerMatching queued : queuedMatching) {
            CoreMessage command = queued.command();
            long sequence = owner.nextAppliedSequence();
            CommandSlot pending = newPendingMatching(sequence, CommandSlot.Operation.TRIGGER, command);
            pending.triggerAdmission(queued.order());
            pending.sourceTrigger(queued.trigger());
            pending.establishCommitFence(clusterTimestamp, clusterPosition);
            owner.matchingFlow.putPendingMatching(pending);
            registerPendingLifecycle(pending);
            owner.recordAppliedSequence(sequence, null, 0L);
            owner.matchingFlow.submitMatching(pending);
        }
        queuedMatching.clear();
    }

    CommandSlot newPendingMatching(long sequence, CommandSlot.Operation operation,
                                               CoreMessage command) {
        return newPendingMatching(sequence, operation, command, owner.fingerprints.of(command));
    }

    CommandSlot newPendingMatching(long sequence, CommandSlot.Operation operation,
                                               CoreMessage command, CommandFingerprint fingerprint) {
        return owner.pendingMatching.acquire(sequence, operation, command, fingerprint, List.of(),
                com.surprising.aeron.service.state.RuntimeFundsDelta.empty(),
                owner.decodeMatchingCommand(command), null);
    }

    CommandSlot newPendingMatching(long sequence, CommandSlot.Operation operation,
                                               CoreMessage command, CommandFingerprint fingerprint,
                                               DecodedMatchingCommand decodedCommand) {
        return owner.pendingMatching.acquire(sequence, operation, command, fingerprint, List.of(),
                com.surprising.aeron.service.state.RuntimeFundsDelta.empty(), decodedCommand, null);
    }

    CommandSlot newPendingMatching(long sequence, CommandSlot.Operation operation,
                                               CoreMessage command, List<Long> preMatchingCancellations) {
        return owner.pendingMatching.acquire(sequence, operation, command, owner.fingerprints.of(command),
                preMatchingCancellations,
                com.surprising.aeron.service.state.RuntimeFundsDelta.empty(),
                owner.decodeMatchingCommand(command), null);
    }

    CommandSlot newPendingMatching(long sequence, CommandSlot.Operation operation,
                                               CoreMessage command, List<Long> preMatchingCancellations,
                                               DecodedMatchingCommand decodedCommand,
                                               ResolvedMatchingAdmission admission) {
        return owner.pendingMatching.acquire(sequence, operation, command, owner.fingerprints.of(command),
                preMatchingCancellations,
                owner.runtimeState.coldCommandFunds(), decodedCommand, admission);
    }

    CommandSlot newPendingMatching(long sequence, CommandSlot.Operation operation,
                                               CoreMessage command, CommandFingerprint fingerprint,
                                               List<Long> preMatchingCancellations,
                                               DecodedMatchingCommand decodedCommand,
                                               ResolvedMatchingAdmission admission) {
        return owner.pendingMatching.acquire(sequence, operation, command, fingerprint, preMatchingCancellations,
                owner.runtimeState.coldCommandFunds(),
                decodedCommand, admission);
    }

    CoreResponse beginMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                       TradingCoreRuntime.SourceKey sourceKey, CommandFingerprint fingerprint) {
        CommandSlot.Operation operation = matchingOperation(message.header().messageType());
        if (owner.batches.hasPendingBatches() && !owner.commandIngress.clusterPipelineAdmission()) {
            return deferMatching(message, clusterTimestamp, clusterPosition, sourceKey, operation, fingerprint);
        }
        return prepareMatching(message, clusterTimestamp, clusterPosition, sourceKey, operation, fingerprint, null);
    }

    static CommandSlot.Operation matchingOperation(CoreMessageType messageType) {
        return switch (messageType) {
            case PLACE_ORDER, PLACE_ORDER_BATCH -> CommandSlot.Operation.PLACE;
            case CANCEL_ORDER, CANCEL_ORDER_BATCH -> CommandSlot.Operation.CANCEL;
            case REPLACE_ORDER -> CommandSlot.Operation.REPLACE;
            case AMEND_ORDER, AMEND_ORDER_BATCH -> CommandSlot.Operation.AMEND;
            case EXECUTE_LIQUIDATION -> CommandSlot.Operation.LIQUIDATION;
            case EXECUTE_LIQUIDATION_BATCH -> CommandSlot.Operation.LIQUIDATION_BATCH;
            case SETTLE_INSTRUMENT -> CommandSlot.Operation.SETTLEMENT;
            default -> throw new IllegalArgumentException("not a matching command");
        };
    }

    CoreResponse prepareMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                         TradingCoreRuntime.SourceKey sourceKey, CommandSlot.Operation operation,
                                         CommandFingerprint fingerprint, CommandSlot deferredPending) {
        DecodedMatchingCommand decodedCommand;
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
            CoreResponse response = owner.matchingFlow.recordRejectedMatching(message, sourceKey, effectiveFingerprint,
                    CoreResultCode.fromRejectionCode(exception.code()), deferredPending);
            return owner.finishFactContext(response);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            CoreResponse response = owner.matchingFlow.recordRejectedMatching(message, sourceKey, effectiveFingerprint,
                    exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND, deferredPending);
            return owner.finishFactContext(response);
        }
        long sequence = deferredPending == null
                ? Math.incrementExact(owner.appliedCommandCount) : deferredPending.sequence();
        long runtimeCommandCheckpoint = owner.runtimeState.commandRevisionCheckpoint();
        long positionIdentityCheckpoint = owner.identities.positionCheckpoint();
        List<Long> preMatchingCancellations = List.of();
        ResolvedMatchingAdmission admission = null;
        com.surprising.aeron.service.state.PlaceAdmissionEvent placeAdmission = null;
        com.surprising.aeron.service.state.ReplacementAdmissionEvent replacementAdmission = null;
        owner.resultBuilder.beginCommand();
        owner.commits.beginCommitPublicationBatch();
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
                        operation, message, decodedCommand, placeAdmission);
        } catch (CoreStateRejectedException exception) {
            if (owner.commits.commitPublicationDirty()) {
                if (!owner.pendingMatching.isEmpty()) {
                    throw new IllegalStateException("cannot roll back across an in-flight lane command", exception);
                }
                owner.rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint, sequence);
            }
            else owner.commits.abortCommitPublicationBatch();
            CoreResponse response = owner.matchingFlow.recordRejectedMatching(message, sourceKey, effectiveFingerprint,
                    CoreResultCode.fromRejectionCode(exception.code()), deferredPending);
            return owner.finishFactContext(response);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            if (owner.commits.commitPublicationDirty()) {
                if (!owner.pendingMatching.isEmpty()) {
                    throw new IllegalStateException("cannot roll back across an in-flight lane command", exception);
                }
                owner.rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint, sequence);
            }
            else owner.commits.abortCommitPublicationBatch();
            CoreResponse response = owner.matchingFlow.recordRejectedMatching(message, sourceKey, effectiveFingerprint,
                    exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND, deferredPending);
            return owner.finishFactContext(response);
        }
        boolean tradingStateChanged = owner.commits.commitPublicationDirty()
                && !owner.commits.commitPublicationProvisionalOnly();
        if (tradingStateChanged) {
            try {
                owner.stampOrderChangesRuntime(clusterTimestamp, clusterPosition, owner.resultBuilder.commandChangedOrderIds);
            } catch (RuntimeException exception) {
                owner.rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint, sequence);
                throw exception;
            }
        }
        owner.commits.completeCommitPublicationBatch();
        CommandSlot pending = deferredPending == null
                ? newPendingMatching(sequence, operation, message, effectiveFingerprint,
                        preMatchingCancellations, decodedCommand, admission)
                : deferredPending.withPreMatchingCancellations(preMatchingCancellations)
                        .withAdmission(admission);
        pending.establishCommitFence(clusterTimestamp, clusterPosition);
        if (deferredPending == null) {
            owner.matchingFlow.putPendingMatching(pending);
        } else {
            owner.pendingMatching.put(pending);
            pending.activateDeferredMatching();
        }
        if (placeAdmission != null) pending.placeAdmission(placeAdmission);
        if (replacementAdmission != null) pending.replacementAdmission(replacementAdmission);
        registerPendingLifecycle(pending);
        if (deferredPending == null) {
            owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
        }
        // 当前准入已结束；推进其他序号的异步续接前先释放 owner 的事实上下文。
        owner.clearFactContext();
        owner.runtimeState.releaseOwnerLaneAccess();
        // Matcher input is immutable and already resolved above.  Publish it immediately while
        // the Account Lane performs the funds reservation in parallel; the ordered commit head
        // collects the Lane admission only when both facts are ready.
        owner.matchingFlow.submitMatching(pending);
        return message.matchingPendingResponse(TradingCoreRuntime.matchingPendingCode(), sequence);
    }

    CoreResponse deferMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                       TradingCoreRuntime.SourceKey sourceKey, CommandSlot.Operation operation,
                                       CommandFingerprint fingerprint) {
        DecodedMatchingCommand decodedCommand;
        try {
            decodedCommand = owner.decodeMatchingCommand(message);
        } catch (CoreStateRejectedException rejection) {
            return owner.admissionRejected(CoreResultCode.fromRejectionCode(rejection.code()));
        } catch (ArithmeticException | IllegalArgumentException rejection) {
            return owner.admissionRejected(rejection instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
        }
        long sequence = owner.nextAppliedSequence();
        CommandSlot pending = newPendingMatching(sequence, operation, message, fingerprint, decodedCommand);
        owner.matchingFlow.putPendingMatching(pending);
        pending.deferMatching(clusterTimestamp, clusterPosition, sourceKey);
        owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
        return message.matchingPendingResponse(TradingCoreRuntime.matchingPendingCode(), sequence);
    }

    CoreResponse recordRejectedDeferredMatching(CommandSlot pending, CoreResultCode resultCode) {
        owner.matchingFlow.commitMatchingSequence(pending.sequence());
        owner.resultLedger.storeOwnedResult(pending.command().header().commandId(), pending.fingerprint(),
                ResponseStatus.REJECTED, resultCode, pending.sequence(),
                TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        owner.matchingFlow.removePendingMatching(pending.sequence());
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                pending.sequence(), TradingCoreRuntime.EMPTY_RESPONSE_DATA);
    }

    void validatePendingCancel(CoreMessage message, DecodedMatchingCommand decodedCommand) {
        var command = decodedCommand.cancelOrder();
        var order = owner.activeOrderIndex.activeOrderRoute(command.orderId());
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != message.header().userId()) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        owner.resultBuilder.setSingleChangedUser(message.header().userId());
        owner.resultBuilder.setSingleChangedOrder(command.orderId());
    }

    com.surprising.aeron.service.state.ReplacementAdmissionEvent dispatchReplacementAdmission(
            CoreMessage message, DecodedMatchingCommand decodedCommand, boolean amend, long sequence) {
        long originalOrderId = amend ? decodedCommand.amendOrder().originalOrderId()
                : decodedCommand.replaceOrder().originalOrderId();
        var order = owner.activeOrderIndex.activeOrderRoute(originalOrderId);
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != message.header().userId()) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        String instrumentId = amend ? order.instrumentId()
                : decodedCommand.replaceOrder().replacement().instrumentId();
        var decision = CoreOrderDecisionResolver.context(owner.runtimeState, owner.identities,
                message.header().userId(), instrumentId, owner.currentClusterTimestamp);
        owner.resultBuilder.setSingleChangedUser(message.header().userId());
        owner.resultBuilder.setSingleChangedOrder(originalOrderId);
        return owner.runtimeState.dispatchReplacementAdmission(sequence, message.header().userId(), originalOrderId,
                amend ? decodedCommand.amendOrder() : null, amend ? null : decodedCommand.replaceOrder(),
                decision,
                owner.matchingAdapter.matcherShardId(instrumentId), owner.identities);
    }

    List<Long> preMatchingCloseCapacityCancellations(CommandSlot.Operation operation,
            CoreMessage message, DecodedMatchingCommand decodedCommand,
            com.surprising.aeron.service.state.PlaceAdmissionEvent placeAdmission) {
        if (operation != CommandSlot.Operation.PLACE) return List.of();
        if (placeAdmission == null) throw new IllegalStateException("place matching requires its Lane admission");
        return placeAdmission.complete() ? placeAdmission.closeCapacityCancellations() : List.of();
    }

    void validatePendingLiquidation(DecodedMatchingCommand decodedCommand) {
        var command = decodedCommand.liquidation();
        var route = owner.liquidationIndex.route(command.liquidationId());
        if (route == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        owner.resultBuilder.setSingleChangedUser(route.userId());
        owner.resultBuilder.commandChangedOrderIds = owner.activeOrderIndex.page(route.userId(), route.instrumentId(),
                command.cursorOrderId(), command.maxOrders()).orderIds();
    }

    void validatePendingLiquidationBatch(DecodedMatchingCommand decodedCommand) {
        var command = decodedCommand.liquidationBatch();
        if (command.riskScanContinuation() != null) {
            var control = owner.runtimeState.riskScanControl();
            if (!control.enabled() || command.maxRiskScanUsers() > control.scanBatchSize()) {
                throw new CoreStateRejectedException("INVALID_COMMAND",
                        "risk scan continuation exceeds current control");
            }
            var scan = owner.runtimeState.firstRiskIncompleteScan();
            var continuation = command.riskScanContinuation();
            if (scan == null
                    || !owner.identities.instrumentId(scan.symbolId()).equals(continuation.instrumentId())
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
            var liquidation = owner.liquidationIndex.route(action.liquidationId());
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
            ensureLifecycleScopeAvailable(new LifecycleScope(false, liquidation.userId(),
                    liquidationSymbol,
                    action.liquidationId(), true, false));
            batchChangedUsers.add(liquidation.userId());
            if (remaining > 0) {
                TradingCoreRuntime.LifecycleOrderChunk chunk = owner.matchingFlow.lifecycleOrders(liquidation.userId(), liquidationSymbol,
                        action.cursorOrderId(), remaining);
                for (var order : chunk.orders()) batchChangedOrders.add(order.orderId());
                remaining -= chunk.orders().size();
            }
        }
        owner.resultBuilder.commandChangedUserIds = batchChangedUsers.toImmutableList();
        owner.resultBuilder.commandChangedOrderIds = batchChangedOrders.toImmutableList();
    }

    void validatePendingSettlement(DecodedMatchingCommand decodedCommand) {
        var command = decodedCommand.settlement();
        var instrument = owner.runtimeState.instrument(command.instrumentId());
        if (instrument != null) ProductTradingRulesRegistry.forInstrument(instrument)
                .validateLifecycleSettlement(instrument, command);
        if (instrument != null && !instrument.administrativeSettlement(command)
                && instrument.expiryEpochMillis() > owner.currentClusterTimestamp) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "instrument has not reached expiry");
        }
        var progress = owner.runtimeLifecycleProgress(command.instrumentId());
        if (progress == null && (command.cursorUserId() != 0 || command.cursorOrderId() != 0)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor must start at zero");
        }
        if (progress != null && (progress.settlementId() != command.settlementId()
                || progress.settlementPriceTicks() != command.settlementPriceTicks()
                || progress.optionCashUnitsPerContract() != command.optionCashUnitsPerContract()
                || progress.ordersComplete() != (command.cursorOrderId() == 0)
                || progress.nextCursorOrderId() != command.cursorOrderId()
                || progress.nextCursorUserId() != command.cursorUserId())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor does not match progress");
        }
        TradingCoreRuntime.LifecycleOrderChunk orderChunk = owner.matchingFlow.lifecycleOrders(0, command.instrumentId(), command.cursorOrderId(), command.maxOrders());
        boolean orderPhase = progress == null || !progress.ordersComplete();
        if (orderPhase && !orderChunk.more()) {
            owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(orderChunk.orders());
            owner.resultBuilder.addChangedUsersFromOrders(orderChunk.orders());
        } else if (orderPhase) {
            owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(orderChunk.orders());
            owner.resultBuilder.addChangedUsersFromOrders(orderChunk.orders());
        } else {
            owner.resultBuilder.commandChangedOrderIds = List.of();
        }
    }

    void rejectLifecycleOverlap(CoreMessage message, CommandSlot.Operation operation,
                                        DecodedMatchingCommand decodedCommand) {
        if (operation == CommandSlot.Operation.LIQUIDATION || operation == CommandSlot.Operation.SETTLEMENT) {
            ensureLifecycleScopeAvailable(lifecycleScope(message, operation, decodedCommand));
        } else {
            ensureLifecycleScopeAvailable(message.header().userId(),
                    matchingSymbol(message, operation, decodedCommand), null);
        }
    }

    void ensureLifecycleScopeAvailable(LifecycleScope candidate) {
        ensureLifecycleScopeAvailable(candidate.userId(), candidate.instrumentId(), candidate);
    }

    /** Ordinary orders carry their existing routing fields, without allocating a retained lifecycle scope. */
    private void ensureLifecycleScopeAvailable(long userId, String instrumentId, LifecycleScope candidate) {
        if (instrumentId.isBlank()) return;
        boolean lifecycle = candidate != null && candidate.lifecycle();
        boolean settlement = candidate != null && candidate.settlement();
        long lifecycleId = candidate == null ? 0 : candidate.lifecycleId();
        boolean orderChanging = candidate == null || candidate.orderChanging();
        if (lifecycle) {
            owner.pendingMatching.forEach(pending -> {
                if (pendingLifecycleConflicts(candidate, pending)) {
                    throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                            "matching lifecycle scope is in progress");
                }
            });
        } else if (!pendingLifecycleScopes.isEmpty()) {
            for (List<LifecycleScope> scopes : pendingLifecycleScopes.values()) {
                for (int i = 0; i < scopes.size(); i++) {
                    LifecycleScope scope = scopes.get(i);
                    if (instrumentId.equals(scope.instrumentId()) && scope.lifecycle()
                            && (settlement || scope.settlement() || userId == scope.userId())) {
                        throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                                "matching lifecycle scope is in progress");
                    }
                }
            }
        }
        Integer candidateSymbolId = owner.identities.findSymbolId(instrumentId);
        if (candidateSymbolId != null && owner.runtimeState.treasury().fundingProgress(candidateSymbolId) != null) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "funding position cut is in progress");
        }
        boolean settlementProgress = candidateSymbolId != null
                && owner.runtimeState.treasury().lifecycleProgress(candidateSymbolId) != null;
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
                    && owner.runtimeState.treasury().lifecycleProgress(candidateSymbolId).settlementId()
                    == lifecycleId;
            if (settlementProgress && !settlementContinuation || liquidationActive) {
                throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                        "matching lifecycle scope is in progress");
            }
        }
    }

    /** Control admission runs behind its fence and reads the existing committed active index. */
    private boolean hasCommittedLiquidationConflict(long userId, int symbolId, long excludedId) {
        for (long id : owner.liquidationIndex.activeIds()) {
            if (id == excludedId) continue;
            var liquidation = owner.liquidationIndex.route(id);
            if (liquidation != null && (userId == 0 || liquidation.userId() == userId)
                    && liquidation.instrumentId().equals(owner.identities.instrumentId(symbolId))
                    && liquidation.executable()) return true;
        }
        return false;
    }


    boolean pendingLifecycleConflicts(LifecycleScope candidate, CommandSlot pending) {
        if (pending.operation() != CommandSlot.Operation.LIQUIDATION_BATCH) {
            return conflicts(candidate, lifecycleScope(pending));
        }
        var batch = pending.decodedCommand().liquidationBatch();
        return batch.actions().stream().map(action -> new LifecycleScope(false, action.userId(), action.instrumentId(),
                        action.liquidationId(), true, false)).anyMatch(scope -> conflicts(candidate, scope));
    }

    void registerPendingLifecycle(CommandSlot pending) {
        List<LifecycleScope> scopes = switch (pending.operation()) {
            case LIQUIDATION, SETTLEMENT -> List.of(lifecycleScope(pending));
            case LIQUIDATION_BATCH -> pending.decodedCommand().liquidationBatch()
                    .actions().stream()
                    .map(action -> new LifecycleScope(false, action.userId(), action.instrumentId(),
                            action.liquidationId(), true, false))
                    .toList();
            default -> List.of();
        };
        if (!scopes.isEmpty()) pendingLifecycleScopes.put(pending.sequence(), scopes);
    }

    LifecycleScope lifecycleScope(CoreMessage message, CommandSlot.Operation operation,
                                          DecodedMatchingCommand decodedCommand) {
        return switch (operation) {
            case LIQUIDATION -> {
                var command = decodedCommand.liquidation();
                var liquidation = owner.liquidationIndex.route(command.liquidationId());
                yield liquidation == null ? new LifecycleScope(false, 0, "", 0, true, false)
                        : new LifecycleScope(false, liquidation.userId(), liquidation.instrumentId(),
                                command.liquidationId(), true, false);
            }
            case SETTLEMENT -> new LifecycleScope(true, 0, decodedCommand.settlement().instrumentId(),
                    decodedCommand.settlement().settlementId(), true, false);
            default -> new LifecycleScope(false, message.header().userId(),
                    matchingSymbol(message, operation, decodedCommand), 0, false, true);
        };
    }

    LifecycleScope lifecycleScope(CommandSlot pending) {
        if (pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH) {
            var action = pending.decodedCommand().liquidationBatch().actions().getFirst();
            return new LifecycleScope(false, action.userId(), action.instrumentId(), action.liquidationId(), true, false);
        }
        return lifecycleScope(pending.command(), pending.operation(), pending.decodedCommand());
    }

    static boolean conflicts(LifecycleScope left, LifecycleScope right) {
        if (left.instrumentId().isBlank() || !left.instrumentId().equals(right.instrumentId())
                || (!left.lifecycle() && !right.lifecycle())) return false;
        return left.settlement() || right.settlement() || left.userId() == right.userId();
    }

    record LifecycleScope(boolean settlement, long userId, String instrumentId, long lifecycleId,
                                  boolean lifecycle, boolean orderChanging) {
    }

    String matchingSymbol(CoreMessage message, CommandSlot.Operation operation,
                                  DecodedMatchingCommand decodedCommand) {
        return switch (operation) {
            case PLACE -> decodedCommand.placeOrder().instrumentId();
            case CANCEL -> {
                var command = decodedCommand.cancelOrder();
                var order = owner.activeOrderIndex.activeOrderRoute(command.orderId());
                yield order == null ? "" : order.instrumentId();
            }
            case REPLACE, AMEND -> {
                long orderId = operation == CommandSlot.Operation.REPLACE
                        ? decodedCommand.replaceOrder().originalOrderId()
                        : decodedCommand.amendOrder().originalOrderId();
                var order = owner.activeOrderIndex.activeOrderRoute(orderId);
                yield order == null ? "" : order.instrumentId();
            }
            case TRIGGER -> {
                long[] execute = decodedCommand.trigger();
                var trigger = owner.triggerOrderIndex.route(execute[0]);
                yield trigger == null ? "" : trigger.instrumentId();
            }
            case LIQUIDATION, SETTLEMENT -> "";
            case LIQUIDATION_BATCH -> {
                var batch = decodedCommand.liquidationBatch();
                yield batch.actions().isEmpty() ? "" : batch.actions().getFirst().instrumentId();
            }
        };
    }

    String pendingLifecycleSymbol(CommandSlot pending) {
        if (pending.operation() == CommandSlot.Operation.SETTLEMENT) {
            return pending.decodedCommand().settlement().instrumentId();
        }
        if (pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH) {
            var batch = pending.decodedCommand().liquidationBatch();
            return batch.actions().isEmpty() ? "" : batch.actions().getFirst().instrumentId();
        }
        var liquidation = owner.liquidationIndex.route(
                pending.decodedCommand().liquidation().liquidationId());
        return liquidation == null ? "" : liquidation.instrumentId();
    }

}
