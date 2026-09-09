package com.surprising.aeron.service.execution;

import static com.surprising.aeron.service.execution.TradingCoreRuntime.*;

import com.surprising.aeron.service.execution.CommandResultLedger.StoredResult;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.state.CoreStateRejectedException;
import com.surprising.aeron.service.state.PositionCloseCapacity;
import com.surprising.aeron.service.state.RuntimeProjectionPoint;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.RuntimeSettlementProcessor;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/** 撮合命令准入：校验订单与生命周期依赖，登记延后命令，不提交未完成结算。 */
final class MatchingCommandAdmission {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingCoreRuntime owner;

    MatchingCommandAdmission(TradingCoreRuntime owner) { this.owner = owner; }

    /** 在途清算或交割的资源范围，防止真实资金依赖交错。 */
    LinkedHashMap<Long, List<LifecycleScope>> pendingLifecycleScopes;

    /** 尚不具备执行条件的匹配命令及其日志时间位置。 */
    LinkedHashMap<Long, DeferredMatching> deferredMatching;

    /** 由触发等业务产生、等待正式登记的撮合命令。 */
    final List<CoreMessage> queuedMatching = new ArrayList<>();

    void appendQueuedMatching() {
        if (queuedMatching.isEmpty()) return;
        if (owner.currentAdmission == null) {
            throw new IllegalStateException("queued matching admission reservation is missing");
        }
        owner.currentAdmission.retainHolders(queuedMatching.size());
        for (CoreMessage command : queuedMatching) {
            long sequence = Math.incrementExact(owner.appliedCommandCount);
            PendingMatching pending = newPendingMatching(sequence, PendingMatching.Operation.TRIGGER, command)
                    .withCapacityReservation(owner.currentAdmission);
            owner.putPendingMatching(pending);
            registerPendingLifecycle(pending);
            owner.appliedCommandCount = sequence;
            owner.refreshCommittedCoreSequence();
            owner.submitMatching(pending);
        }
        queuedMatching.clear();
    }

    PendingMatching newPendingMatching(long sequence, PendingMatching.Operation operation,
                                               CoreMessage command) {
        return newPendingMatching(sequence, operation, command, CommandFingerprint.of(command));
    }

    PendingMatching newPendingMatching(long sequence, PendingMatching.Operation operation,
                                               CoreMessage command, CommandFingerprint fingerprint) {
        return owner.pendingMatching.acquire(sequence, operation, command, fingerprint, List.of(),
                owner.currentProjectionPoint, owner.currentBusinessStateHash(), owner.auditFundsStateHash,
                com.surprising.aeron.service.state.RuntimeFundsDelta.empty(),
                owner.decodeMatchingCommand(command), null);
    }

    PendingMatching newPendingMatching(long sequence, PendingMatching.Operation operation,
                                               CoreMessage command, CommandFingerprint fingerprint,
                                               DecodedMatchingCommand decodedCommand) {
        return owner.pendingMatching.acquire(sequence, operation, command, fingerprint, List.of(),
                owner.currentProjectionPoint, owner.currentBusinessStateHash(), owner.auditFundsStateHash,
                com.surprising.aeron.service.state.RuntimeFundsDelta.empty(), decodedCommand, null);
    }

    PendingMatching newPendingMatching(long sequence, PendingMatching.Operation operation,
                                               CoreMessage command, List<Long> preMatchingCancellations) {
        return owner.pendingMatching.acquire(sequence, operation, command, CommandFingerprint.of(command),
                preMatchingCancellations, owner.currentProjectionPoint, owner.currentBusinessStateHash(), owner.auditFundsStateHash,
                com.surprising.aeron.service.state.RuntimeFundsDelta.empty(),
                owner.decodeMatchingCommand(command), null);
    }

    PendingMatching newPendingMatching(long sequence, PendingMatching.Operation operation,
                                               CoreMessage command, List<Long> preMatchingCancellations,
                                               RuntimeProjectionPoint beforeProjection, long beforeBusinessStateHash,
                                               long beforeFundsStateHash,
                                               DecodedMatchingCommand decodedCommand,
                                               ResolvedMatchingAdmission admission) {
        return owner.pendingMatching.acquire(sequence, operation, command, CommandFingerprint.of(command),
                preMatchingCancellations, beforeProjection, beforeBusinessStateHash, beforeFundsStateHash,
                owner.commandFundsAccumulator.toDelta(), decodedCommand, admission);
    }

    PendingMatching newPendingMatching(long sequence, PendingMatching.Operation operation,
                                               CoreMessage command, CommandFingerprint fingerprint,
                                               List<Long> preMatchingCancellations,
                                               RuntimeProjectionPoint beforeProjection, long beforeBusinessStateHash,
                                               long beforeFundsStateHash, DecodedMatchingCommand decodedCommand,
                                               ResolvedMatchingAdmission admission) {
        return owner.pendingMatching.acquire(sequence, operation, command, fingerprint, preMatchingCancellations,
                beforeProjection, beforeBusinessStateHash, beforeFundsStateHash, owner.commandFundsAccumulator.toDelta(),
                decodedCommand, admission);
    }

    CoreResponse beginMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                       TradingCoreRuntime.SourceKey sourceKey, CommandFingerprint fingerprint) {
        PendingMatching.Operation operation = matchingOperation(message.header().messageType());
        if (!owner.batches.pendingOrderBatches.isEmpty() && !owner.clusterPipelineAdmission) {
            return deferMatching(message, clusterTimestamp, clusterPosition, sourceKey, operation, fingerprint);
        }
        return prepareMatching(message, clusterTimestamp, clusterPosition, sourceKey, operation, fingerprint, null);
    }

    int matchingOrderBound(CoreMessage message, DecodedMatchingCommand decodedCommand) {
        if (decodedCommand == null) throw new IllegalArgumentException("decoded matching command is required");
        int inFlightOrders = owner.pendingMatching.size();
        if (!owner.batches.pendingOrderBatches.isEmpty()) {
            inFlightOrders = Math.addExact(inFlightOrders, PlaceOrderBatchCommand.MAX_ORDERS);
        }
        return switch (message.header().messageType()) {
            case PLACE_ORDER -> {
                PlaceOrderCommand command = decodedCommand.placeOrder();
                yield Math.addExact(owner.activeOrderIndex.count(command.symbol()), inFlightOrders);
            }
            case PLACE_ORDER_BATCH -> {
                PlaceOrderBatchCommand command = decodedCommand.placeOrderBatch();
                int activeOrders = 0;
                for (PlaceOrderCommand order : command.orders()) {
                    activeOrders = Math.max(activeOrders, owner.activeOrderIndex.count(order.symbol()));
                }
                yield Math.addExact(Math.addExact(activeOrders, inFlightOrders), command.orders().size());
            }
            default -> 0;
        };
    }

    static PendingMatching.Operation matchingOperation(CoreMessageType messageType) {
        return switch (messageType) {
            case PLACE_ORDER, PLACE_ORDER_BATCH -> PendingMatching.Operation.PLACE;
            case CANCEL_ORDER, CANCEL_ORDER_BATCH -> PendingMatching.Operation.CANCEL;
            case REPLACE_ORDER -> PendingMatching.Operation.REPLACE;
            case AMEND_ORDER, AMEND_ORDER_BATCH -> PendingMatching.Operation.AMEND;
            case EXECUTE_LIQUIDATION -> PendingMatching.Operation.LIQUIDATION;
            case EXECUTE_LIQUIDATION_BATCH -> PendingMatching.Operation.LIQUIDATION_BATCH;
            case SETTLE_INSTRUMENT -> PendingMatching.Operation.SETTLEMENT;
            default -> throw new IllegalArgumentException("not a matching command");
        };
    }

    CoreResponse prepareMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                         TradingCoreRuntime.SourceKey sourceKey, PendingMatching.Operation operation,
                                         CommandFingerprint fingerprint, PendingMatching deferredPending) {
        long matchingStartNanos = System.nanoTime();
        DecodedMatchingCommand decodedCommand;
        CoreAdmissionReservation capacityReservation;
        try {
            decodedCommand = deferredPending == null
                    ? owner.decodeMatchingCommand(message) : deferredPending.decodedCommand();
            capacityReservation = deferredPending == null
                    ? owner.reserveAdmission(CoreAdmissionReservation.AdmissionDemand.matching(
                            message, matchingOrderBound(message, decodedCommand), decodedCommand))
                    : owner.sequenceAdmission(deferredPending.sequence());
            if (capacityReservation == null) {
                throw new IllegalStateException("deferred matching admission reservation is missing");
            }
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
        owner.activateFactContext(capacityReservation, message, effectiveFingerprint);
        try {
            rejectLifecycleOverlap(message, operation, decodedCommand);
        } catch (CoreStateRejectedException exception) {
            CoreResponse response = owner.recordRejectedMatching(message, sourceKey, effectiveFingerprint,
                    CoreResultCode.fromRejectionCode(exception.code()), deferredPending);
            return owner.releaseAdmission(capacityReservation, response);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            CoreResponse response = owner.recordRejectedMatching(message, sourceKey, effectiveFingerprint,
                    exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND, deferredPending);
            return owner.releaseAdmission(capacityReservation, response);
        }
        RuntimeProjectionPoint beforeProjection = deferredPending == null
                ? owner.currentProjectionPoint : deferredPending.beforeProjection();
        long beforeBusinessStateHash = deferredPending == null
                ? owner.currentBusinessStateHash() : deferredPending.beforeBusinessStateHash();
        long beforeFundsStateHash = deferredPending == null
                ? owner.auditFundsStateHash : deferredPending.beforeFundsStateHash();
        long sequence = deferredPending == null
                ? Math.incrementExact(owner.appliedCommandCount) : deferredPending.sequence();
        long runtimeCommandCheckpoint = owner.runtimeState.commandRevisionCheckpoint();
        long positionIdentityCheckpoint = owner.identities.positionCheckpoint();
        List<Long> preMatchingCancellations = List.of();
        ResolvedMatchingAdmission admission = null;
        com.surprising.aeron.service.state.PlaceAdmissionEvent placeAdmission = null;
        owner.resultBuilder.commandOrderViews = List.of();
        owner.resultBuilder.commandChangedUserIds = List.of();
        owner.resultBuilder.commandChangedOrderIds = List.of();
        owner.resultBuilder.commandTradeCount = 0;
        owner.resultBuilder.commandFundingProgress = null;
        owner.resultBuilder.commandLiquidationProgress = null;
        owner.resultBuilder.commandLiquidationBatchResult = null;
        owner.resultBuilder.commandSettlementProgress = null;
        owner.resultBuilder.commandRiskScanControl = null;
        owner.resultBuilder.resetChangeAccumulators();
        owner.commits.beginCommitPublicationBatch();
        try {
            switch (operation) {
                case PLACE -> {
                    var command = decodedCommand.placeOrder();
                    owner.requireOrderIdentityAvailable(message.header().userId(), command);
                    placeAdmission = owner.dispatchPlaceAdmission(message.header().userId(), command,
                            message.header().commandId(), sequence);
                }
                case CANCEL -> validatePendingCancel(message, decodedCommand);
                case REPLACE -> admission = validatePendingReplace(message, decodedCommand, false);
                case AMEND -> admission = validatePendingReplace(message, decodedCommand, true);
                case TRIGGER -> validatePendingTrigger(decodedCommand);
                case LIQUIDATION -> validatePendingLiquidation(decodedCommand);
                case LIQUIDATION_BATCH -> validatePendingLiquidationBatch(decodedCommand);
                case SETTLEMENT -> validatePendingSettlement(decodedCommand);
            }
            preMatchingCancellations = preMatchingCloseCapacityCancellations(
                    operation, message, decodedCommand, admission);
        } catch (CoreStateRejectedException exception) {
            if (owner.commits.commitPublicationDirty) {
                if (!owner.pendingMatching.isEmpty()) {
                    throw new IllegalStateException("cannot roll back across an in-flight lane command", exception);
                }
                owner.rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint, sequence);
            }
            else owner.commits.abortCommitPublicationBatch();
            CoreResponse response = owner.recordRejectedMatching(message, sourceKey, effectiveFingerprint,
                    CoreResultCode.fromRejectionCode(exception.code()), deferredPending);
            return owner.releaseAdmission(capacityReservation, response);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            if (owner.commits.commitPublicationDirty) {
                if (!owner.pendingMatching.isEmpty()) {
                    throw new IllegalStateException("cannot roll back across an in-flight lane command", exception);
                }
                owner.rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint, sequence);
            }
            else owner.commits.abortCommitPublicationBatch();
            CoreResponse response = owner.recordRejectedMatching(message, sourceKey, effectiveFingerprint,
                    exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND, deferredPending);
            return owner.releaseAdmission(capacityReservation, response);
        }
        boolean tradingStateChanged = owner.commits.commitPublicationDirty && !owner.commits.commitPublicationProvisionalOnly;
        if (tradingStateChanged) {
            try {
                owner.stampOrderChangesRuntime(clusterTimestamp, clusterPosition, owner.resultBuilder.commandChangedOrderIds);
            } catch (RuntimeException exception) {
                owner.rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint, sequence);
                throw exception;
            }
        }
        owner.commits.completeCommitPublicationBatch();
        try {
        } catch (RuntimeException exception) {
            throw new IllegalStateException("matching delta failed after typed state commit", exception);
        }
        long businessStateHash = tradingStateChanged ? owner.currentBusinessStateHash() : owner.cachedBusinessStateHash;
        long requiredExportSequence = 0;
        PendingMatching pending = deferredPending == null
                ? newPendingMatching(sequence, operation, message, effectiveFingerprint,
                        preMatchingCancellations, beforeProjection,
                        beforeBusinessStateHash, beforeFundsStateHash, decodedCommand, admission)
                        .withCapacityReservation(capacityReservation)
                : deferredPending.withPreMatchingCancellations(preMatchingCancellations)
                        .withAdmission(admission);
        pending.establishCommitFence(clusterTimestamp, clusterPosition);
        if (deferredPending == null) {
            owner.putPendingMatching(pending);
        } else {
            owner.pendingMatching.put(pending);
            deferredMatching.remove(sequence);
        }
        if (placeAdmission != null) pending.placeAdmission(placeAdmission);
        registerPendingLifecycle(pending);
        if (deferredPending == null) {
            owner.appliedCommandCount = sequence;
            owner.refreshCommittedCoreSequence();
            owner.recordSourceSequence(sourceKey, message.header().sourceSequence());
        }
        long stateHash = owner.stateHash(owner.cachedBusinessStateHash, message.header().commandId(), ResponseStatus.OK,
                TradingCoreRuntime.matchingPendingCode(), sequence);
        byte[] responseData = TradingCoreRuntime.EMPTY_RESPONSE_DATA;
        pending.withPendingStateHash(stateHash);
        if (TradingCoreRuntime.MATCHING_PHASE_METRICS_ENABLED) {
            owner.matchingPhaseMetrics.recordPrepare(System.nanoTime() - matchingStartNanos);
        }
        if (placeAdmission == null) owner.submitMatching(pending);
        else owner.progressPlaceAdmissions();
        owner.clearFactContext();
        return CoreResponse.owned(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(),
                sequence, requiredExportSequence, stateHash, responseData);
    }

    CoreResponse deferMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                       TradingCoreRuntime.SourceKey sourceKey, PendingMatching.Operation operation,
                                       CommandFingerprint fingerprint) {
        DecodedMatchingCommand decodedCommand;
        CoreAdmissionReservation reservation;
        try {
            decodedCommand = owner.decodeMatchingCommand(message);
            reservation = owner.reserveAdmission(
                    CoreAdmissionReservation.AdmissionDemand.matching(message, matchingOrderBound(message, decodedCommand),
                            decodedCommand));
        } catch (CoreStateRejectedException rejection) {
            return owner.admissionRejected(CoreResultCode.fromRejectionCode(rejection.code()));
        } catch (ArithmeticException | IllegalArgumentException rejection) {
            return owner.admissionRejected(rejection instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
        }
        long sequence = Math.incrementExact(owner.appliedCommandCount);
        PendingMatching pending = newPendingMatching(sequence, operation, message, fingerprint, decodedCommand)
                .withCapacityReservation(reservation);
        owner.putPendingMatching(pending);
        deferredMatching.put(sequence, new DeferredMatching(clusterTimestamp, clusterPosition, sourceKey));
        owner.appliedCommandCount = sequence;
        owner.refreshCommittedCoreSequence();
        owner.recordSourceSequence(sourceKey, message.header().sourceSequence());
        long stateHash = owner.stateHash(owner.cachedBusinessStateHash, message.header().commandId(), ResponseStatus.OK,
                TradingCoreRuntime.matchingPendingCode(), sequence);
        pending.withPendingStateHash(stateHash);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(),
                sequence, 0, stateHash, TradingCoreRuntime.EMPTY_RESPONSE_DATA);
    }

    CoreResponse recordRejectedDeferredMatching(PendingMatching pending, CoreResultCode resultCode) {
        owner.commitMatchingSequence(pending.sequence());
        long requiredExportSequence = 0;
        long stateHash = owner.stateHash(owner.cachedBusinessStateHash, pending.command().header().commandId(),
                ResponseStatus.REJECTED, resultCode, pending.sequence());
        owner.resultLedger.storeResult(pending.command().header().commandId(), new StoredResult(pending.fingerprint(),
                ResponseStatus.REJECTED, resultCode, pending.sequence(), requiredExportSequence, stateHash,
                new byte[0], 0));
        deferredMatching.remove(pending.sequence());
        owner.removePendingMatching(pending.sequence());
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                pending.sequence(), requiredExportSequence, stateHash, new byte[0]);
    }

    void validatePendingCancel(CoreMessage message, DecodedMatchingCommand decodedCommand) {
        var command = decodedCommand.cancelOrder();
        var order = owner.runtimeState.order(command.orderId());
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != message.header().userId()) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        owner.resultBuilder.commandChangedUserIds = List.of(message.header().userId());
        owner.resultBuilder.commandChangedOrderIds = List.of(command.orderId());
    }

    ResolvedMatchingAdmission validatePendingReplace(
            CoreMessage message, DecodedMatchingCommand decodedCommand, boolean amend) {
        long originalOrderId = amend ? decodedCommand.amendOrder().originalOrderId()
                : decodedCommand.replaceOrder().originalOrderId();
        var order = owner.runtimeState.order(originalOrderId);
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != message.header().userId()) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        if (order.status() != com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "order is not replaceable");
        }
        if (amend && order.orderType() != com.surprising.aeron.protocol.CoreOrderType.LIMIT) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "order is not amendable");
        }
        PlaceOrderCommand replacement = owner.replacementFor(decodedCommand,
                amend ? PendingMatching.Operation.AMEND : PendingMatching.Operation.REPLACE, order);
        if (replacement.orderId() != originalOrderId) {
            owner.requireOrderIdentityAvailable(message.header().userId(), replacement);
        }
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(owner.runtimeState,
                owner.identities, message.header().userId(), replacement, owner.currentClusterTimestamp);
        long requiredReservation = com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservation(
                owner.runtimeState, owner.identities, message.header().userId(), resolved,
                owner.openInterestIndex.openInterestSteps(replacement.symbol()), owner.activeOrderIndex, originalOrderId);
        owner.resultBuilder.commandChangedUserIds = List.of(message.header().userId());
        owner.resultBuilder.commandChangedOrderIds = List.of(originalOrderId);
        var user = owner.runtimeState.user(message.header().userId());
        var matchingOrder = new CoreMatchingOrder(resolved.orderId(), resolved.symbol(), resolved.side(),
                resolved.orderType(), resolved.timeInForce(), resolved.matchingPriceTicks(),
                resolved.quantitySteps());
        return new ResolvedMatchingAdmission(message.header().userId(), originalOrderId, order.revision(),
                user == null ? 0 : user.revision(), replacement, resolved, matchingOrder, requiredReservation);
    }

    void validatePendingTrigger(DecodedMatchingCommand decodedCommand) {
        long[] execute = decodedCommand.trigger();
        var trigger = owner.runtimeState.triggerOrder(execute[0]);
        if (trigger == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order does not exist");
        }
        PlaceOrderCommand child = owner.triggerPlacement(trigger, execute[2]);
        var instrument = owner.runtimeState.instrument(child.symbol());
        if (instrument != null) instrument.requireTrading(false);
        var order = owner.runtimeState.order(child.orderId());
        if (order == null || order.userId() != trigger.userId()) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND", "trigger child reservation is missing");
        }
        owner.resultBuilder.commandChangedUserIds = List.of(trigger.userId());
        owner.resultBuilder.commandChangedOrderIds = List.of(child.orderId());
    }

    List<Long> preMatchingCloseCapacityCancellations(
            PendingMatching.Operation operation,
            CoreMessage message,
            DecodedMatchingCommand decodedCommand,
            ResolvedMatchingAdmission admission) {
        PlaceOrderCommand placement;
        long excludedOrderId = 0;
        switch (operation) {
            case PLACE -> {
                placement = decodedCommand.placeOrder();
                excludedOrderId = placement.orderId();
            }
            case TRIGGER -> {
                long[] execute = decodedCommand.trigger();
                var trigger = owner.runtimeState.triggerOrder(execute[0]);
                if (trigger == null) return List.of();
                placement = owner.triggerPlacement(trigger, execute[2]);
                excludedOrderId = placement.orderId();
            }
            case REPLACE, AMEND -> {
                if (admission == null) throw new IllegalStateException("replace admission is missing");
                excludedOrderId = admission.originalOrderId();
                placement = admission.command();
            }
            default -> {
                return List.of();
            }
        }
        org.eclipse.collections.impl.set.mutable.primitive.LongHashSet cancellations =
                new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet();
        List<Long> closeCapacity = preMatchingCloseCapacityCancellations(
                message.header().userId(), placement, excludedOrderId);
        for (int index = 0; index < closeCapacity.size(); index++) {
            cancellations.add(closeCapacity instanceof ImmutableLongArrayList primitive
                    ? primitive.valueAt(index) : closeCapacity.get(index));
        }
        long[] selfTrade = preMatchingSelfTradeCancellations(
                message.header().userId(), placement, excludedOrderId);
        for (long orderId : selfTrade) cancellations.add(orderId);
        long[] ordered = cancellations.toArray();
        java.util.Arrays.sort(ordered);
        return ImmutableLongArrayList.takeOwnership(ordered);
    }

    long[] preMatchingSelfTradeCancellations(
            long userId,
            PlaceOrderCommand placement,
            long excludedOrderId) {
        long matchingPrice = CoreOrderDecisionResolver.resolve(owner.runtimeState,
                owner.identities, userId, placement, owner.currentClusterTimestamp).matchingPriceTicks();
        var candidates = owner.activeOrderIndex.matchingIds(userId, placement.symbol());
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList cancellations = null;
        while (candidates.hasNext()) {
            long orderId = candidates.next();
            if (orderId == excludedOrderId || orderId == placement.orderId()) continue;
            OrderRuntime order = owner.runtimeOrder(orderId);
            if (order == null || order.status() != com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN
                    || order.side() == placement.side()) continue;
            boolean crosses = placement.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                    ? matchingPrice >= order.priceTicks() : matchingPrice <= order.priceTicks();
            if (crosses) {
                if (cancellations == null) {
                    cancellations = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
                }
                cancellations.add(orderId);
            }
        }
        if (cancellations == null) return TradingCoreRuntime.EMPTY_ORDER_IDS;
        long[] result = cancellations.toArray();
        java.util.Arrays.sort(result);
        return result;
    }

    List<Long> preMatchingCloseCapacityCancellations(
            long userId,
            PlaceOrderCommand placement,
            long excludedOrderId) {
        if (!owner.productLine.isDerivative() || placement.reduceOnly()) return List.of();
        var user = owner.runtimeState.user(userId);
        if (user == null) return List.of();
        String symbol = placement.symbol();
        String positionKey = placement.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                ? symbol : symbol + ':' + placement.positionSide().name();
        Long runtimePositionKey = owner.identities.findPositionKey(userId, positionKey);
        var position = runtimePositionKey == null ? null : owner.runtimeState.position(runtimePositionKey);
        if (position == null || position.signedQuantitySteps() == 0
                || (position.signedQuantitySteps() > 0)
                == (placement.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
            return List.of();
        }
        return PositionCloseCapacity.inspectRuntime(owner.runtimeState, owner.identities, userId,
                symbol, placement.positionSide(), placement.side(), owner.activeOrderIndex, excludedOrderId)
                .conflictsFor(placement.quantitySteps());
    }

    void validatePendingLiquidation(DecodedMatchingCommand decodedCommand) {
        var command = decodedCommand.liquidation();
        var liquidation = owner.runtimeState.liquidation(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        if (liquidation.status() == CoreLiquidationState.Status.PLANNED && command.cursorOrderId() != 0
                || liquidation.status() == CoreLiquidationState.Status.ORDERED
                && command.cursorOrderId() != liquidation.nextCancelOrderId()) {
            throw new CoreStateRejectedException("LIQUIDATION_CURSOR_CONFLICT",
                    "liquidation cancellation cursor does not match state");
        }
        if (liquidation.status() != CoreLiquidationState.Status.PLANNED
                && liquidation.status() != CoreLiquidationState.Status.ORDERED) {
            throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "liquidation is not executable");
        }
        owner.resultBuilder.commandChangedUserIds = List.of(liquidation.userId());
        TradingCoreRuntime.LifecycleOrderChunk chunk = owner.lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                command.cursorOrderId(), command.maxOrders());
        owner.resultBuilder.commandChangedOrderIds = chunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList();
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
                    || !owner.identities.symbol(scan.symbolId()).equals(continuation.symbol())
                    || scan.priceSequence() != continuation.priceSequence()
                    || scan.lastUserId() != continuation.lastUserId()) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "risk scan cursor does not match state");
            }
        }
        java.util.HashSet<String> scopes = new java.util.HashSet<>();
        List<Long> changedUsers = new ArrayList<>();
        List<Long> changedOrders = new ArrayList<>();
        int remaining = command.maxCancelOrders();
        for (var action : command.actions()) {
            var liquidation = owner.runtimeState.liquidation(action.liquidationId());
            if (liquidation == null || liquidation.status() == CoreLiquidationState.Status.COMPLETED
                    || liquidation.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED
                    || liquidation.status() == CoreLiquidationState.Status.ADL_REQUIRED
                    || liquidation.status() == CoreLiquidationState.Status.CANCELED) continue;
            if (liquidation.userId() != action.userId()
                    || !owner.runtimeLiquidationSymbol(liquidation).equals(action.symbol())
                    || liquidation.instrumentChangeId() != action.instrumentChangeId()
                    || liquidation.triggerPriceSequence() != action.triggerPriceSequence()
                    || action.executionPriceTicks() <= 0) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "liquidation batch action does not match state");
            }
            if (liquidation.status() == CoreLiquidationState.Status.PLANNED && action.cursorOrderId() != 0
                    || liquidation.status() == CoreLiquidationState.Status.ORDERED
                    && action.cursorOrderId() != liquidation.nextCancelOrderId()) {
                throw new CoreStateRejectedException("LIQUIDATION_CURSOR_CONFLICT",
                        "liquidation batch cursor does not match state");
            }
            if (!scopes.add(liquidation.userId() + "\u0000" + owner.runtimeLiquidationSymbol(liquidation))) {
                throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                        "liquidation batch contains overlapping scopes");
            }
            ensureLifecycleScopeAvailable(new LifecycleScope(false, liquidation.userId(),
                    owner.runtimeLiquidationSymbol(liquidation),
                    liquidation.liquidationId(), true, false));
            changedUsers.add(liquidation.userId());
            if (remaining > 0) {
                TradingCoreRuntime.LifecycleOrderChunk chunk = owner.lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                        action.cursorOrderId(), remaining);
                changedOrders.addAll(chunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList());
                remaining -= chunk.orders().size();
            }
        }
        owner.resultBuilder.commandChangedUserIds = changedUsers.stream().distinct().toList();
        owner.resultBuilder.commandChangedOrderIds = changedOrders.stream().distinct().toList();
    }

    void validatePendingSettlement(DecodedMatchingCommand decodedCommand) {
        var command = decodedCommand.settlement();
        var instrument = owner.runtimeState.instrument(command.symbol());
        if (instrument != null) RuntimeSettlementProcessor.validateSettlement(instrument,
                com.surprising.aeron.service.state.ProductTradingRulesRegistry.forInstrument(instrument),command);
        if (instrument != null && !instrument.administrativeSettlement(command)
                && instrument.expiryEpochMillis() > owner.currentClusterTimestamp) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "instrument has not reached expiry");
        }
        var progress = owner.runtimeLifecycleProgress(command.symbol());
        if (progress == null && (command.cursorUserId() != 0 || command.cursorOrderId() != 0)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor must start at zero");
        }
        if (progress != null && (progress.settlementId() != command.settlementId()
                || progress.instrumentChangeId() != command.instrumentChangeId()
                || progress.settlementPriceTicks() != command.settlementPriceTicks()
                || progress.optionCashUnitsPerContract() != command.optionCashUnitsPerContract()
                || progress.ordersComplete() != (command.cursorOrderId() == 0)
                || progress.nextCursorOrderId() != command.cursorOrderId()
                || progress.nextCursorUserId() != command.cursorUserId())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor does not match progress");
        }
        TradingCoreRuntime.LifecycleOrderChunk orderChunk = owner.lifecycleOrders(0, command.symbol(), command.cursorOrderId(), command.maxOrders());
        boolean orderPhase = progress == null || !progress.ordersComplete();
        if (orderPhase && !orderChunk.more()) {
            owner.resultBuilder.commandChangedOrderIds = orderChunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList();
            owner.resultBuilder.commandChangedUserIds = orderChunk.orders().stream().map(CoreOrderState::userId).distinct().toList();
            owner.resultBuilder.commandChangedUserIds = TradingCoreRuntime.appendDistinct(owner.resultBuilder.commandChangedUserIds,
                    owner.instrumentSettlement.settlementUsers(command.symbol(), command.cursorUserId(), command.maxUsers()));
        } else if (orderPhase) {
            owner.resultBuilder.commandChangedOrderIds = orderChunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList();
            owner.resultBuilder.commandChangedUserIds = orderChunk.orders().stream().map(CoreOrderState::userId).distinct().toList();
        } else {
            owner.resultBuilder.commandChangedOrderIds = List.of();
            owner.resultBuilder.commandChangedUserIds = owner.instrumentSettlement.settlementUsers(command.symbol(), command.cursorUserId(), command.maxUsers());
        }
    }

    void rejectLifecycleOverlap(CoreMessage message, PendingMatching.Operation operation,
                                        DecodedMatchingCommand decodedCommand) {
        LifecycleScope candidate = lifecycleScope(message, operation, decodedCommand);
        if (candidate.symbol().isBlank()) return;
        ensureLifecycleScopeAvailable(candidate);
    }

    void ensureLifecycleScopeAvailable(LifecycleScope candidate) {
        if (candidate.symbol().isBlank()) return;
        if (candidate.lifecycle()) {
            owner.pendingMatching.forEach(pending -> {
                if (pendingLifecycleConflicts(candidate, pending)) {
                    throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                            "matching lifecycle scope is in progress");
                }
            });
        } else {
            for (List<LifecycleScope> scopes : pendingLifecycleScopes.values()) {
                if (scopes.stream().anyMatch(scope -> conflicts(candidate, scope))) {
                    throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                            "matching lifecycle scope is in progress");
                }
            }
        }
        Integer candidateSymbolId = owner.identities.findSymbolId(candidate.symbol());
        if (candidateSymbolId != null && owner.runtimeState.treasury().fundingProgress(candidateSymbolId) != null) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "funding position cut is in progress");
        }
        boolean settlementProgress = candidateSymbolId != null
                && owner.runtimeState.treasury().lifecycleProgress(candidateSymbolId) != null;
        if (settlementProgress
                && candidate.orderChanging()) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                    "settlement lifecycle is in progress");
        }
        if (candidate.lifecycle()) {
            boolean liquidationActive = candidateSymbolId != null
                    && owner.runtimeState.hasActiveLiquidationConflict(
                    candidate.settlement() ? 0 : candidate.userId(), candidateSymbolId, candidate.lifecycleId());
            boolean settlementContinuation = settlementProgress && candidate.settlement()
                    && owner.runtimeState.treasury().lifecycleProgress(candidateSymbolId).settlementId()
                    == candidate.lifecycleId();
            if (settlementProgress && !settlementContinuation || liquidationActive) {
                throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                        "matching lifecycle scope is in progress");
            }
        }
    }

    boolean pendingLifecycleConflicts(LifecycleScope candidate, PendingMatching pending) {
        if (pending.operation() != PendingMatching.Operation.LIQUIDATION_BATCH) {
            return conflicts(candidate, lifecycleScope(pending));
        }
        var batch = pending.decodedCommand().liquidationBatch();
        return batch.actions().stream().map(action -> new LifecycleScope(false, action.userId(), action.symbol(),
                        action.liquidationId(), true, false)).anyMatch(scope -> conflicts(candidate, scope));
    }

    void registerPendingLifecycle(PendingMatching pending) {
        List<LifecycleScope> scopes = switch (pending.operation()) {
            case LIQUIDATION, SETTLEMENT -> List.of(lifecycleScope(pending));
            case LIQUIDATION_BATCH -> pending.decodedCommand().liquidationBatch()
                    .actions().stream()
                    .map(action -> new LifecycleScope(false, action.userId(), action.symbol(),
                            action.liquidationId(), true, false))
                    .toList();
            default -> List.of();
        };
        if (!scopes.isEmpty()) pendingLifecycleScopes.put(pending.sequence(), scopes);
    }

    LifecycleScope lifecycleScope(CoreMessage message, PendingMatching.Operation operation,
                                          DecodedMatchingCommand decodedCommand) {
        return switch (operation) {
            case LIQUIDATION -> {
                var command = decodedCommand.liquidation();
                var liquidation = owner.runtimeState.liquidation(command.liquidationId());
                yield liquidation == null ? new LifecycleScope(false, 0, "", 0, true, false)
                        : new LifecycleScope(false, liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                                liquidation.liquidationId(), true, false);
            }
            case SETTLEMENT -> new LifecycleScope(true, 0, decodedCommand.settlement().symbol(),
                    decodedCommand.settlement().settlementId(), true, false);
            default -> new LifecycleScope(false, message.header().userId(),
                    matchingSymbol(message, operation, decodedCommand), 0, false, true);
        };
    }

    LifecycleScope lifecycleScope(PendingMatching pending) {
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH) {
            var action = pending.decodedCommand().liquidationBatch().actions().getFirst();
            return new LifecycleScope(false, action.userId(), action.symbol(), action.liquidationId(), true, false);
        }
        return lifecycleScope(pending.command(), pending.operation(), pending.decodedCommand());
    }

    static boolean conflicts(LifecycleScope left, LifecycleScope right) {
        if (left.symbol().isBlank() || !left.symbol().equals(right.symbol())
                || (!left.lifecycle() && !right.lifecycle())) return false;
        return left.settlement() || right.settlement() || left.userId() == right.userId();
    }

    record LifecycleScope(boolean settlement, long userId, String symbol, long lifecycleId,
                                  boolean lifecycle, boolean orderChanging) {
    }

    String matchingSymbol(CoreMessage message, PendingMatching.Operation operation,
                                  DecodedMatchingCommand decodedCommand) {
        return switch (operation) {
            case PLACE -> decodedCommand.placeOrder().symbol();
            case CANCEL -> {
                var command = decodedCommand.cancelOrder();
                var order = owner.runtimeState.order(command.orderId());
                yield order == null ? "" : owner.identities.symbol(order.symbolId());
            }
            case REPLACE, AMEND -> {
                long orderId = operation == PendingMatching.Operation.REPLACE
                        ? decodedCommand.replaceOrder().originalOrderId()
                        : decodedCommand.amendOrder().originalOrderId();
                var order = owner.runtimeState.order(orderId);
                yield order == null ? "" : owner.identities.symbol(order.symbolId());
            }
            case TRIGGER -> {
                long[] execute = decodedCommand.trigger();
                var trigger = owner.runtimeState.triggerOrder(execute[0]);
                yield trigger == null ? "" : trigger.symbol();
            }
            case LIQUIDATION, SETTLEMENT -> "";
            case LIQUIDATION_BATCH -> {
                var batch = decodedCommand.liquidationBatch();
                yield batch.actions().isEmpty() ? "" : batch.actions().getFirst().symbol();
            }
        };
    }

    String pendingLifecycleSymbol(PendingMatching pending) {
        if (pending.operation() == PendingMatching.Operation.SETTLEMENT) {
            return pending.decodedCommand().settlement().symbol();
        }
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH) {
            var batch = pending.decodedCommand().liquidationBatch();
            return batch.actions().isEmpty() ? "" : batch.actions().getFirst().symbol();
        }
        var liquidation = owner.runtimeState.liquidation(
                pending.decodedCommand().liquidation().liquidationId());
        return liquidation == null ? "" : owner.runtimeLiquidationSymbol(liquidation);
    }

    record DeferredMatching(long clusterTimestamp, long clusterPosition, TradingCoreRuntime.SourceKey sourceKey) {
    }
}
