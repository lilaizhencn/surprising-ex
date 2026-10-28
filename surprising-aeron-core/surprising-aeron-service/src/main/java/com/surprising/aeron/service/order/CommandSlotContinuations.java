package com.surprising.aeron.service.order;

import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.order.admission.PlaceAdmissionEvent;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.commit.OrderedCommitCoordinator;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.settlement.MatcherSettlementEvent;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 命令槽在撮合与控制轮询阶段复用的各类连续任务（Continuation）。
 * 绑定于宿主 {@link CommandSlot}，避免在每秒数十万笔高频交易的热路径上频繁分配 Lambda/闭包对象。
 */
final class ControlMatchingContinuation implements Supplier<Object> {
    private final CommandSlot slot;
    private TradingCoreRuntime owner;
    private List<CancellationOrder> orders;

    ControlMatchingContinuation(CommandSlot slot) {
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
    private final CommandSlot slot;
    private TradingCoreRuntime owner;
    private PlaceAdmissionEvent admission;
    private int matcherShard;
    private MatcherSettlementEvent settlement;
    private Supplier<?> original;

    AdmissionMatchingContinuation(CommandSlot slot) {
        this.slot = Objects.requireNonNull(slot);
    }

    void prepare(TradingCoreRuntime owner, PlaceAdmissionEvent admission, int matcherShard,
                 MatcherSettlementEvent settlement, Supplier<?> original) {
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
                    orderId, place.instrumentId());
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

final class PlaceMatchingContinuation implements Supplier<Object> {
    private final CommandSlot slot;
    private TradingCoreRuntime owner;
    private int matcherShard;
    private long userId;
    private ResolvedPlaceOrder resolvedOrder;
    private OrderRuntime admittedOrder;
    private MatcherSettlementEvent direct;

    PlaceMatchingContinuation(CommandSlot slot) {
        this.slot = Objects.requireNonNull(slot);
    }

    void prepare(TradingCoreRuntime owner, int matcherShard, long userId,
                 ResolvedPlaceOrder resolvedOrder, OrderRuntime admittedOrder, MatcherSettlementEvent direct) {
        if ((resolvedOrder == null) == (admittedOrder == null)) {
            throw new IllegalArgumentException("exactly one admitted PLACE order representation is required");
        }
        this.owner = Objects.requireNonNull(owner);
        this.matcherShard = matcherShard;
        this.userId = userId;
        this.resolvedOrder = resolvedOrder;
        this.admittedOrder = admittedOrder;
        this.direct = Objects.requireNonNull(direct, "direct settlement");
    }

    void clear() {
        owner = null;
        matcherShard = 0;
        userId = 0;
        resolvedOrder = null;
        admittedOrder = null;
        direct = null;
    }

    @Override
    public Object get() {
        long orderId = admittedOrder != null ? admittedOrder.orderId()
                : resolvedOrder.orderId();
        var failure = slot.applyMatcherPreCancellations(owner, matcherShard, orderId, direct);
        if (failure != null) return failure;
        long msb = slot.command().header().commandIdMsb();
        long lsb = slot.command().header().commandIdLsb();
        if (admittedOrder != null) {
            return owner.matchingAdapter().placeAdmittedDirect(matcherShard, slot.coreSequence, msb, lsb,
                    slot.command().header().submittedAtEpochMillis(), userId, admittedOrder, direct);
        }
        return owner.matchingAdapter().placeDirect(matcherShard, slot.coreSequence, msb, lsb,
                slot.command().header().submittedAtEpochMillis(), userId, resolvedOrder, direct);
    }
}

final class CancelMatchingContinuation implements Supplier<Object> {
    private final CommandSlot slot;
    private TradingCoreRuntime owner;
    private int matcherShard;
    private long orderId;
    private long userId;
    private String instrumentId;
    private MatcherSettlementEvent direct;

    CancelMatchingContinuation(CommandSlot slot) {
        this.slot = Objects.requireNonNull(slot);
    }

    void prepare(TradingCoreRuntime owner, int matcherShard, long orderId,
                 long userId, String instrumentId, MatcherSettlementEvent direct) {
        this.owner = Objects.requireNonNull(owner);
        this.matcherShard = matcherShard;
        this.orderId = orderId;
        this.userId = userId;
        this.instrumentId = Objects.requireNonNull(instrumentId);
        this.direct = Objects.requireNonNull(direct, "direct settlement");
    }

    void clear() {
        owner = null;
        matcherShard = 0;
        orderId = 0;
        userId = 0;
        instrumentId = null;
        direct = null;
    }

    @Override
    public Object get() {
        var failure = slot.applyMatcherPreCancellations(owner, matcherShard, orderId, direct);
        if (failure != null) return failure;
        return owner.matchingAdapter().cancelDirect(matcherShard, slot.coreSequence,
                slot.command().header().commandIdMsb(), slot.command().header().commandIdLsb(), orderId,
                slot.command().header().submittedAtEpochMillis(), userId, instrumentId, direct);
    }
}

final class ReplaceMatchingContinuation implements Supplier<Object> {
    private final CommandSlot slot;
    private TradingCoreRuntime owner;
    private int matcherShard;
    private long orderId;
    private long userId;
    private long originalOrderId;
    private String instrumentId;
    private ResolvedPlaceOrder resolvedReplacement;
    private MatcherSettlementEvent direct;

    ReplaceMatchingContinuation(CommandSlot slot) {
        this.slot = Objects.requireNonNull(slot);
    }

    void prepare(TradingCoreRuntime owner, int matcherShard, long orderId,
                 long userId, long originalOrderId, String instrumentId, ResolvedPlaceOrder replacement,
                 MatcherSettlementEvent direct) {
        this.owner = Objects.requireNonNull(owner);
        this.matcherShard = matcherShard;
        this.orderId = orderId;
        this.userId = userId;
        this.originalOrderId = originalOrderId;
        this.instrumentId = Objects.requireNonNull(instrumentId);
        this.resolvedReplacement = Objects.requireNonNull(replacement);
        this.direct = Objects.requireNonNull(direct);
    }

    void clear() {
        owner = null;
        matcherShard = 0;
        orderId = 0;
        userId = 0;
        originalOrderId = 0;
        instrumentId = null;
        resolvedReplacement = null;
        direct = null;
    }

    @Override
    public Object get() {
        var failure = slot.applyMatcherPreCancellations(owner, matcherShard, orderId, direct);
        if (failure != null) return failure;
        return owner.matchingAdapter().replaceDirect(matcherShard, slot.coreSequence,
                slot.command().header().commandIdMsb(), slot.command().header().commandIdLsb(), orderId,
                slot.command().header().submittedAtEpochMillis(), userId, originalOrderId, instrumentId, resolvedReplacement, direct);
    }
}

final class SettlementControlContinuation implements BooleanSupplier {
    private OrderedCommitCoordinator coordinator;
    private com.surprising.aeron.service.settlement.lifecycle.RuntimeLifecycleSettlementContinuation.SettlementWork work;

    void prepare(OrderedCommitCoordinator coordinator,
                 com.surprising.aeron.service.settlement.lifecycle.RuntimeLifecycleSettlementContinuation.SettlementWork work) {
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
    private OrderedCommitCoordinator coordinator;
    private com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor.ExecutionWork work;
    private com.surprising.aeron.protocol.CoreLiquidationProgressView progress;

    void prepare(OrderedCommitCoordinator coordinator,
                 com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor.ExecutionWork work,
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
