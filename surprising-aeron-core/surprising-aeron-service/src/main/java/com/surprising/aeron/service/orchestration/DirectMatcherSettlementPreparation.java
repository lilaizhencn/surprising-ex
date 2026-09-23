package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.state.MatcherSettlementEvent;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;

/**
 * Prepares the direct Matcher-to-Account-Lane settlement for normal and batch commands.
 *
 * <p>The event is reserved before Matcher submission, while the runtime keeps ownership of
 * command slots, commit ordering and the actual Lane publication.</p>
 */
final class DirectMatcherSettlementPreparation {
    private final TradingCoreRuntime owner;

    DirectMatcherSettlementPreparation(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    MatcherSettlementEvent prepareForMatching(CommandSlot pending) {
        if (pending.operation() == CommandSlot.Operation.REPLACE
                || pending.operation() == CommandSlot.Operation.AMEND) {
            return prepareReplacement(pending);
        }
        if (!owner.runtimeState.asynchronousCommands()) return null;
        return switch (pending.operation()) {
            case PLACE -> preparePlace(pending);
            case TRIGGER -> prepareTrigger(pending);
            case CANCEL -> prepareCancellation(pending);
            default -> null;
        };
    }

    MatcherSettlementEvent preparePipelinedPlaceBatch(
            CommandSlot pending, OrderBatchPending batch, int shard) {
        MatcherSettlementEvent direct = owner.runtimeState.prepareDirectMatcherSettlement(
                pending.sequence(), owner.commits.validAccountLaneMask(),
                null, batch.preparedAdmittedOrders, batch.items.size(), pending.command().header().commandId(),
                shard, owner.identities, pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), batch);
        reserveMatcherPublication(direct);
        return direct;
    }

    MatcherSettlementEvent prepareBatchPlaceItem(
            CommandSlot pending, OrderBatchPending batch, OrderRuntime admitted, int shard) {
        MatcherSettlementEvent direct = owner.runtimeState.prepareDirectMatcherSettlement(
                pending.sequence(), 0, owner.commits.validAccountLaneMask(), admitted, null, 1,
                pending.command().header().commandId(), shard, owner.identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), batch);
        reserveMatcherPublication(direct);
        return direct;
    }

    MatcherSettlementEvent prepareBatchAmend(
            CommandSlot pending, ResolvedMatchingAdmission admission, int shard) {
        MatcherSettlementEvent direct = owner.runtimeState.prepareDirectReplacement(
                pending.sequence(), 0, owner.commits.validAccountLaneMask(), admission,
                pending.command().header().commandId(), shard, owner.identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds());
        reserveMatcherPublication(direct);
        return direct;
    }

    MatcherSettlementEvent prepareCancelBatch(
            CommandSlot pending, OrderBatchPending batch, int shard, boolean finalChunk, int count) {
        MatcherSettlementEvent direct = owner.runtimeState.prepareDirectCancelBatch(
                pending.sequence(), finalChunk, pending.command().header().userId(),
                batch.preparedAdmittedOrders, count, pending.command().header().commandId(), shard,
                owner.identities, pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(), batch);
        reserveMatcherPublication(direct);
        return direct;
    }

    private MatcherSettlementEvent preparePlace(CommandSlot pending) {
        OrderRuntime directTaker = pending.placeAdmission() == null
                ? pending.realtimeTakerOrder != null
                        ? pending.realtimeTakerOrder
                        : owner.runtimeOrder(pending.decodedCommand().placeOrder().orderId())
                : null;
        MatcherSettlementEvent direct = null;
        if (pending.placeAdmission() != null) {
            direct = owner.runtimeState.prepareDirectMatcherSettlement(
                    pending.sequence(), directInitialLaneMask(pending.placeAdmission().userId()),
                    pending.placeAdmission(), pending.command().header().commandId(), owner.matchingFlow.matcherShard(pending),
                    owner.identities, pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                    pending.preMatchingCancellationOrderIds(), pending.laneResultTarget());
        } else if (directTaker != null) {
            direct = owner.runtimeState.prepareDirectMatcherSettlement(
                    pending.sequence(), directInitialLaneMask(directTaker.userId()), directTaker, null, 1,
                    pending.command().header().commandId(), owner.matchingFlow.matcherShard(pending), owner.identities,
                    pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                    pending.preMatchingCancellationOrderIds(), pending.laneResultTarget());
        }
        reserveMatcherPublication(pending, direct);
        // A completed admission already placed its immutable receipt in the command slot.
        // Do not erase it merely because the final Owner projection is intentionally deferred.
        if (directTaker != null) pending.realtimeTakerOrder = directTaker;
        return direct;
    }

    private MatcherSettlementEvent prepareReplacement(CommandSlot pending) {
        var admission = owner.requireMatchingAdmission(pending);
        owner.requireUnchangedAdmissionState(admission);
        MatcherSettlementEvent direct = owner.runtimeState.prepareDirectReplacement(
                pending.sequence(), pending.sequence(), directInitialLaneMask(admission.userId()), admission,
                pending.command().header().commandId(), owner.matchingFlow.matcherShard(pending), owner.identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), pending.laneResultTarget());
        pending.settlement(direct, direct.plan(), System.nanoTime());
        if (owner.runtimeState.asynchronousCommands()) reserveMatcherPublication(direct);
        if (owner.realtimeCapture != null) pending.realtimeTakerOrder = direct.admittedOrder();
        return direct;
    }

    private MatcherSettlementEvent prepareTrigger(CommandSlot pending) {
        long[] execute = pending.decodedCommand().trigger();
        var trigger = java.util.Objects.requireNonNull(owner.runtimeState.triggerOrder(execute[0]),
                "admitted trigger is missing");
        OrderRuntime triggerOrder = owner.runtimeOrder(pending.admittedMatchingOrder().orderId());
        MatcherSettlementEvent direct = owner.runtimeState.prepareDirectMatcherSettlement(pending.sequence(),
                directInitialLaneMask(triggerOrder.userId()), triggerOrder, null, 1,
                pending.command().header().commandId(), owner.matchingFlow.matcherShard(pending), owner.identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), pending.laneResultTarget());
        direct.triggerCompletion(trigger, execute[3]);
        reserveMatcherPublication(pending, direct);
        if (owner.realtimeCapture != null) pending.realtimeTakerOrder = triggerOrder;
        return direct;
    }

    private MatcherSettlementEvent prepareCancellation(CommandSlot pending) {
        OrderRuntime canceledOrder = owner.runtimeOrder(pending.decodedCommand().cancelOrder().orderId());
        MatcherSettlementEvent direct = owner.runtimeState.prepareDirectCancellation(pending.sequence(), canceledOrder,
                pending.command().header().commandId(), owner.matchingFlow.matcherShard(pending), owner.identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(), pending.laneResultTarget());
        reserveMatcherPublication(pending, direct);
        return direct;
    }

    private void reserveMatcherPublication(CommandSlot pending, MatcherSettlementEvent direct) {
        pending.settlement(direct, direct.plan(), System.nanoTime());
        reserveMatcherPublication(direct);
    }

    private void reserveMatcherPublication(MatcherSettlementEvent direct) {
        direct.markMatcherOwnedPublication();
        direct.reserveMatcherPublication();
    }

    private long directInitialLaneMask(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("direct settlement user is required");
        return owner.runtimeState.topology().accountLaneMask(userId);
    }
}
