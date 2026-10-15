package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import com.surprising.aeron.service.command.ImmutableLongArrayList;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.service.state.RuntimeFundsDelta;
import com.surprising.aeron.service.state.RuntimeFundsAccumulator;
import com.surprising.aeron.service.state.PlaceAdmissionEvent;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.MatcherSettlementEvent;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder;
import java.util.List;
import java.util.Objects;
import com.surprising.aeron.service.command.support.PrimitiveLongChangeSet;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.matching.MatchingResult;
import com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor;

/**
 * Reusable command lifetime: input, business progress, Lane completion and final result.
 * Owner writes orchestration fields; matcher publication follows MatcherCompletionRoute.
 * The ring's claimed bit controls reuse, while sequence remains readable until the next claim.
 */
public final class CommandSlot implements com.surprising.aeron.service.state.MatcherSettlementEvent.MatcherCompletionRoute {
    /** Optional batch context owned by this pending command, detached before command-slot reuse. */
    OrderBatchPending orderBatch;
    private Operation operation;
    private CoreMessage command;
    private CommandFingerprint fingerprint;
    private List<Long> preMatchingCancellationOrderIds;
    private final RuntimeFundsAccumulator fundsAccumulator = new RuntimeFundsAccumulator();
    private DecodedMatchingCommand decodedCommand;
    private ResolvedMatchingAdmission admission;
    private long commitFenceTimestamp;
    private long commitFenceClusterPosition;
    private boolean commitFenceEstablished;
    /** The preconstructed Direct settlement belongs to this command until terminal retirement. */
    private com.surprising.aeron.service.state.MatcherSettlementEvent settlementContinuation;
    /** Normal PLACE admission runs alongside the settlement continuation. */
    private PlaceAdmissionEvent placeAdmission;
    private boolean placeAdmissionOwnerCollected;
    private com.surprising.aeron.service.state.ReplacementAdmissionEvent replacementAdmission;
    private com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan;
    private long settlementApplyStartNanos;
    /** 普通 PLACE 已在 Owner 解析，Lane 完成后直接复用该值，避免撮合命令副本。 */
    private ResolvedPlaceOrder admittedPlaceOrder;
    private CoreMatchingOrder admittedMatchingOrder;
    /** 已完成冻结的不可变触发子订单回执，仅保留到本命令槽释放。 */
    private OrderRuntime triggerAdmissionOrder;
    private com.surprising.aeron.service.state.model.CoreTriggerOrderState sourceTrigger;
    /**
     * Compact command lifecycle.  The slot is reused by the pending ring, so a byte is enough
     * to describe the owner-side admission/submission boundary without a second continuation map.
     */
    private byte matchingLifecycle;
    static final byte MATCHING_ADMITTED = 0;
    static final byte MATCHING_DEFERRED = 1;
    static final byte MATCHING_SUBMITTED = 2;
    static final byte MATCHER_DONE = 3;
    static final byte LANES_DONE = 4;
    static final byte COMMITTED = 5;
    /** Deferred commands keep their replay fence in the slot instead of a boxed map entry. */
    private long deferredClusterTimestamp;
    private long deferredClusterPosition;
    private TradingCoreRuntime.SourceKey deferredSourceKey;
    /** 跨分片清算撤单已进入异步协调队列，防止重复派发。 */
    boolean crossShardCancellationStarted;
    boolean clusterIndependent;
    /** This command cannot emit further money; response storage may remain leased. */
    boolean ledgerDispatchClosed;
    /** 准入时固定的潜在成交账户分区，整个命令终态前保持不变。 */
    /** 派发前的不可变订单身份；仅实时推送启用时保留，提交后释放。 */
    com.surprising.aeron.service.state.OrderRuntime realtimeTakerOrder;
    /** Normal PLACE reuses its immutable resolved input for realtime trade identity. */
    ResolvedPlaceOrder realtimeResolvedTakerOrder;
    private boolean pipelinedSettlementCounted;
    /** Cached matcher shard for a non-batch command; stable until this slot is recycled. */
    private int cachedMatcherShard = -1;

    // Matching controls reuse the same continuation fields as settlement/clearing work.
    java.util.function.BooleanSupplier controlWork;
    /** Slot-owned settlement continuation; retained across reuse to avoid per-command lambdas. */
    private com.surprising.aeron.service.state.RuntimeLifecycleSettlementContinuation.SettlementWork reusableSettlementWork;
    private final SettlementControlContinuation settlementControl = new SettlementControlContinuation();
    private com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor.ExecutionWork reusableLiquidationWork;
    /** 仅强平命令首次使用时创建，随槽位复用的Lane前置校验。 */
    private com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor.ExecutionCheck liquidationCheck;
    private final LiquidationExecutionContinuation liquidationExecutionControl =
            new LiquidationExecutionContinuation();
    /** Fixed task for the only admission-gated Matcher submission. */
    private final AdmissionMatchingContinuation admissionMatching = new AdmissionMatchingContinuation();
    /** Stable Owner route inputs, consumed only by this slot's Matcher task and cleared on retirement. */
    private List<CancellationOrder>
            matcherPreCancellations = List.of();
    private final ControlMatchingContinuation controlMatching = new ControlMatchingContinuation();
    /** Fixed wrapper for the ordinary PLACE Matcher submission. */
    private final PlaceMatchingContinuation placeMatching = new PlaceMatchingContinuation();
    /** Fixed wrapper for the ordinary CANCEL Matcher submission. */
    private final CancelMatchingContinuation cancelMatching = new CancelMatchingContinuation();
    /** Fixed wrapper for a resolved ordinary REPLACE/AMEND Matcher submission. */
    private final ReplaceMatchingContinuation replaceMatching = new ReplaceMatchingContinuation();
    /** Slot-owned resolved place order instance reused for zero-allocation amends/replaces. */
    private final ResolvedPlaceOrder reusableResolvedOrder = new ResolvedPlaceOrder();

    ResolvedPlaceOrder reusableResolvedOrder() {
        return reusableResolvedOrder;
    }
    com.surprising.aeron.service.state.LaneCommitEvent commitEvent;

    boolean controlPending;
    /** The Ledger validation poll must not submit the already committed account task again. */
    boolean controlAccountsCommitted;
    long controlStartedNanos;
    byte[] resultData;

    /**
     * Lane-owned response target for a single matching command.  The target stores immutable
     * after-images produced by the settlement Lane, so the Owner does not look the same orders
     * up again while closing the command.  Batch commands use their existing OrderBatchPending
     * target and therefore never allocate another target here.
     */
    private final LaneResultTarget laneResultTarget = new LaneResultTarget();

    void prepareLaneResultTarget(ResponseArena responseArena) {
        if (orderBatch != null) return;
        long first = 0, second = 0;
        switch (operation) {
            case PLACE -> first = decodedCommand.placeOrder().orderId();
            case CANCEL -> first = decodedCommand.cancelOrder().orderId();
            case REPLACE, AMEND -> {
                ResolvedMatchingAdmission value = admission;
                if (value != null) {
                    first = value.originalOrderId();
                    second = value.command().orderId();
                }
            }
            case TRIGGER -> {
                if (admittedMatchingOrder != null) first = admittedMatchingOrder.orderId();
            }
            default -> { }
        }
        laneResultTarget.bind(first, second, responseArena);
    }

    com.surprising.aeron.service.state.LaneOrderResultTarget laneResultTarget() {
        return laneResultTarget.active() ? laneResultTarget : null;
    }

    OrderRuntime laneResultOrder(long orderId) { return laneResultTarget.order(orderId); }
    String laneResultSymbol(long orderId) { return laneResultTarget.instrumentId(orderId); }
    boolean laneResultPrepared() { return laneResultTarget.prepared(); }
    byte[] lanePreparedResponse() { return laneResultTarget.preparedResponse(); }
    int lanePreparedResponseLength() { return laneResultTarget.preparedResponseLength(); }
    void transferLaneResponseOwnership() { laneResultTarget.transferResponseOwnership(); }

    /** Reuses one slot-owned gate instead of allocating a capturing lambda for each PLACE. */
    java.util.function.Supplier<?> gateAdmission(TradingCoreRuntime owner,
            PlaceAdmissionEvent admission, int matcherShard,
            com.surprising.aeron.service.state.MatcherSettlementEvent settlement,
            java.util.function.Supplier<?> original) {
        admissionMatching.prepare(owner, admission, matcherShard, settlement, original);
        return admissionMatching;
    }

    /**
     * Prepares the fixed-slot ordinary PLACE submission without allocating a capturing lambda.
     * The matcher receives either the resolved Lane admission object or the immutable runtime
     * order snapshot already required by the existing path.
     */
    java.util.function.Supplier<?> preparePlaceMatching(TradingCoreRuntime owner,
            int matcherShard, long userId,
            ResolvedPlaceOrder resolvedOrder, CoreMatchingOrder matchingOrder,
            MatcherSettlementEvent direct) {
        placeMatching.prepare(owner, matcherShard, userId, resolvedOrder, matchingOrder, direct);
        return placeMatching;
    }

    /** Sequential batch input is already frozen in its item slot; Matcher consumes that lease directly. */
    java.util.function.Supplier<?> prepareAdmittedPlaceMatching(TradingCoreRuntime owner,
            int matcherShard, long userId, OrderRuntime admittedOrder, MatcherSettlementEvent direct) {
        placeMatching.prepare(owner, matcherShard, userId, null, null, admittedOrder, direct);
        return placeMatching;
    }

    /** Reuses the command slot for the common cancellation path instead of allocating a lambda. */
    java.util.function.Supplier<?> prepareCancelMatching(TradingCoreRuntime owner,
            int matcherShard, long orderId, long userId, String instrumentId, MatcherSettlementEvent direct) {
        cancelMatching.prepare(owner, matcherShard, orderId, userId, instrumentId, direct);
        return cancelMatching;
    }

    /** Reuses the command slot for a resolved replacement without intermediate submission objects. */
    java.util.function.Supplier<?> prepareReplaceMatching(TradingCoreRuntime owner,
            int matcherShard, long orderId, long userId,
            long originalOrderId, String instrumentId, ResolvedPlaceOrder replacement,
            MatcherSettlementEvent direct) {
        replaceMatching.prepare(owner, matcherShard, orderId, userId,
                originalOrderId, instrumentId, replacement, direct);
        return replaceMatching;
    }

    void matcherPreCancellations(List<CancellationOrder> orders) {
        matcherPreCancellations = Objects.requireNonNull(orders);
    }

    java.util.function.Supplier<?> prepareControlMatching(TradingCoreRuntime owner, long orderId,
            List<CancellationOrder> orders) {
        controlMatching.owner = Objects.requireNonNull(owner);
        controlMatching.orderId = orderId;
        controlMatching.orders = Objects.requireNonNull(orders);
        return controlMatching;
    }

    private exchange.core2.core.common.MatcherResult applyMatcherPreCancellations(
            TradingCoreRuntime owner, int shard, long logicalOrderId, MatcherSettlementEvent target) {
        for (int index = 0; index < matcherPreCancellations.size(); index++) {
            var cancellation = matcherPreCancellations.get(index);
            var result = owner.matchingAdapter.cancelDirectPrefix(command.header().submittedAtEpochMillis(),
                    cancellation.userId(), cancellation.orderId(), cancellation.instrumentId());
            boolean accepted = result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                    || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED;
            if (accepted) target.recordDirectPreCancellation(cancellation.orderId());
            else return owner.matchingAdapter.publishDirectPrefixFailure(shard, coreSequence,
                    command.header().commandId(), logicalOrderId, command.header().submittedAtEpochMillis(), result, target);
        }
        return null;
    }

    private final class ControlMatchingContinuation implements java.util.function.Supplier<Object> {
        private TradingCoreRuntime owner;
        private long orderId;
        private List<CancellationOrder> orders;
        @Override public Object get() {
            return owner.matchingAdapter.cancelControlOrders(coreSequence, command.header().commandId(),
                    orderId, command.header().submittedAtEpochMillis(), orders);
        }
        void clear() { owner = null; orderId = 0; orders = null; }
    }

    void deferControl(java.util.function.BooleanSupplier work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        controlWork = Objects.requireNonNull(work);
    }

    com.surprising.aeron.service.state.RuntimeLifecycleSettlementContinuation.SettlementWork settlementWork() {
        return reusableSettlementWork;
    }

    void settlementWork(com.surprising.aeron.service.state.RuntimeLifecycleSettlementContinuation.SettlementWork work) {
        reusableSettlementWork = Objects.requireNonNull(work);
    }

    void deferSettlementControl(OrderedCommitCoordinator coordinator,
            com.surprising.aeron.service.state.RuntimeLifecycleSettlementContinuation.SettlementWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        settlementControl.prepare(coordinator, work);
        controlWork = settlementControl;
    }

    com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor.ExecutionCheck liquidationCheck() {
        if (liquidationCheck == null)
            liquidationCheck = new com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor.ExecutionCheck();
        return liquidationCheck;
    }

    com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor.ExecutionWork liquidationWork() {
        return reusableLiquidationWork;
    }

    void liquidationWork(com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor.ExecutionWork work) {
        reusableLiquidationWork = Objects.requireNonNull(work);
    }

    void deferLiquidationExecutionControl(OrderedCommitCoordinator coordinator,
            com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor.ExecutionWork work,
            com.surprising.aeron.protocol.CoreLiquidationProgressView progress) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        liquidationExecutionControl.prepare(coordinator, work, progress);
        controlWork = liquidationExecutionControl;
    }

    CommandSlot() {
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command,
                    RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, CommandFingerprint.of(command), List.of(), fundsDelta);
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, fingerprint, List.of(), fundsDelta);
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                fundsDelta,
                DecodedMatchingCommand.decode(command));
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    List<Long> preMatchingCancellationOrderIds, RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds,
                fundsDelta,
                DecodedMatchingCommand.decode(command));
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                fundsDelta, decodedCommand, null);
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    List<Long> preMatchingCancellationOrderIds, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand) {
        this(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds,
                fundsDelta, decodedCommand, null);
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                fundsDelta, decodedCommand, admission);
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    List<Long> preMatchingCancellationOrderIds, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        initialize(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds,
                fundsDelta, decodedCommand, admission);
    }

    CommandSlot initialize(long sequence, Operation operation, CoreMessage command,
                               CommandFingerprint fingerprint, List<Long> preMatchingCancellationOrderIds,
                               RuntimeFundsDelta fundsDelta,
                               DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        if (sequence <= 0 || operation == null || command == null || preMatchingCancellationOrderIds == null
                || fingerprint == null || fundsDelta == null || decodedCommand == null
                || command.header().kind() != com.surprising.aeron.protocol.WireMessageKind.COMMAND) {
            throw new IllegalArgumentException("invalid pending matching request");
        }
        Objects.requireNonNull(command.header().commandId(), "commandId");
        clearLifecycleState();
        this.coreSequence = sequence;
        claimed = true;
        orderBatch = null;
        this.operation = operation;
        this.command = command;
        this.fingerprint = fingerprint;
        this.preMatchingCancellationOrderIds = retainCancellationIds(preMatchingCancellationOrderIds);
        fundsAccumulator.clear();
        fundsAccumulator.add(fundsDelta);
        this.decodedCommand = decodedCommand;
        this.admission = admission;
        commitFenceTimestamp = 0;
        commitFenceClusterPosition = 0;
        commitFenceEstablished = false;
        settlementContinuation = null;
        placeAdmission = null;
        placeAdmissionOwnerCollected = false;
        replacementAdmission = null;
        settlementPlan = null;
        settlementApplyStartNanos = 0;
        admittedMatchingOrder = null;
        triggerAdmissionOrder = null;
        sourceTrigger = null;
        admittedPlaceOrder = null;
        matchingLifecycle = MATCHING_ADMITTED;
        deferredClusterTimestamp = 0;
        deferredClusterPosition = 0;
        deferredSourceKey = null;
        crossShardCancellationStarted = false;
        clusterIndependent = false;
        ledgerDispatchClosed = false;
        realtimeTakerOrder = null;
        realtimeResolvedTakerOrder = null;
        pipelinedSettlementCounted = false;
        cachedMatcherShard = -1;
        return this;
    }

    CommandSlot withPreMatchingCancellations(List<Long> orderIds) {
        preMatchingCancellationOrderIds = retainCancellationIds(orderIds);
        return this;
    }

    CommandSlot initialize(long sequence, Operation operation, CoreMessage command,
                           CommandFingerprint fingerprint, List<Long> preMatchingCancellationOrderIds,
                           RuntimeFundsAccumulator sourceFunds,
                           DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        initialize(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds,
                RuntimeFundsDelta.empty(), decodedCommand, admission);
        if (sourceFunds != null) sourceFunds.transferToEmpty(fundsAccumulator);
        return this;
    }

    /** Internal admission paths already hand over immutable primitive storage. Keep it instead of
     * copying into a boxed Object[] for every derivative PLACE/REPLACE command. */
    private static List<Long> retainCancellationIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return List.of();
        if (orderIds instanceof ImmutableLongArrayList) {
            return orderIds;
        }
        return List.copyOf(orderIds);
    }

    CommandSlot withAdmission(ResolvedMatchingAdmission nextAdmission) {
        admission = nextAdmission;
        return this;
    }

    void establishCommitFence(long clusterTimestamp, long clusterPosition) {
        if (clusterTimestamp < 0 || clusterPosition < 0) {
            throw new IllegalArgumentException("matching commit fence cannot be negative");
        }
        if (commitFenceEstablished) return;
        commitFenceTimestamp = clusterTimestamp;
        commitFenceClusterPosition = clusterPosition;
        commitFenceEstablished = true;
    }

    long sequence() { return coreSequence; }
    Operation operation() { return operation; }
    CoreMessage command() { return command; }
    CommandFingerprint fingerprint() { return fingerprint; }
    List<Long> preMatchingCancellationOrderIds() { return preMatchingCancellationOrderIds; }
    RuntimeFundsAccumulator fundsAccumulator() { return fundsAccumulator; }
    DecodedMatchingCommand decodedCommand() { return decodedCommand; }
    ResolvedMatchingAdmission matchingAdmission() { return admission; }
    com.surprising.aeron.service.state.ReplacementAdmissionEvent replacementAdmission() { return replacementAdmission; }
    void replacementAdmission(com.surprising.aeron.service.state.ReplacementAdmissionEvent event) {
        if (event == null || replacementAdmission != null || operation != Operation.AMEND && operation != Operation.REPLACE)
            throw new IllegalStateException("invalid replacement admission slot");
        replacementAdmission = event;
    }
    com.surprising.aeron.service.state.ReplacementAdmissionEvent takeReplacementAdmission() {
        var value = replacementAdmission; replacementAdmission = null; return value;
    }
    com.surprising.aeron.service.state.MatcherSettlementEvent settlementEvent() {
        return settlementContinuation;
    }
    com.surprising.aeron.service.state.MatcherSettlementEvent takeSettlementEvent() {
        var value = settlementEvent();
        settlementContinuation = null;
        return value;
    }
    com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan() { return settlementPlan; }
    long settlementApplyStartNanos() { return settlementApplyStartNanos; }
    PlaceAdmissionEvent placeAdmission() {
        return placeAdmission;
    }
    boolean hasLaneContinuation() {
        return settlementContinuation != null;
    }
    boolean laneContinuationComplete() {
        if (!hasLaneContinuation()) return false;
        return settlementContinuation.complete();
    }
    PlaceAdmissionEvent takePlaceAdmission() {
        PlaceAdmissionEvent value = placeAdmission;
        placeAdmission = null;
        return value;
    }
    void placeAdmission(PlaceAdmissionEvent event) {
        if (event == null || placeAdmission != null || operation != Operation.PLACE) {
            throw new IllegalStateException("invalid place admission continuation");
        }
        placeAdmission = event;
    }
    void admissionCompleted(ResolvedPlaceOrder resolvedOrder) {
        if (resolvedOrder == null || placeAdmission() == null || admittedPlaceOrder != null
                || admittedMatchingOrder != null) {
            throw new IllegalStateException("invalid completed place admission");
        }
        admittedPlaceOrder = resolvedOrder;
    }
    boolean placeAdmissionOwnerCollected() { return placeAdmissionOwnerCollected; }
    void markPlaceAdmissionOwnerCollected() { placeAdmissionOwnerCollected = true; }
    ResolvedPlaceOrder admittedPlaceOrder() { return admittedPlaceOrder; }
    CoreMatchingOrder admittedMatchingOrder() { return admittedMatchingOrder; }
    /** 沿用已完成冻结的不可变订单，触发续步不得再回读 Lane 的客户端订单索引。 */
    com.surprising.aeron.service.state.model.CoreTriggerOrderState sourceTrigger() { return sourceTrigger; }
    void sourceTrigger(com.surprising.aeron.service.state.model.CoreTriggerOrderState value) {
        if (value == null || sourceTrigger != null) throw new IllegalStateException("trigger receipt already bound");
        sourceTrigger = value;
    }
    OrderRuntime triggerAdmissionOrder() { return triggerAdmissionOrder; }
    void triggerAdmission(OrderRuntime order) {
        if (operation != Operation.TRIGGER || order == null || admittedMatchingOrder != null)
            throw new IllegalStateException("invalid trigger admission");
        triggerAdmissionOrder = order;
        admittedMatchingOrder = new CoreMatchingOrder(order.orderId(), order.instrument().instrumentId(),
                order.side(), order.orderType(), order.timeInForce(), order.matchingPriceTicks(),
                order.remainingQuantitySteps(), CoreMatchingOrder.slippagePpm(order.productLine()));
    }
    boolean isMatchingSubmitted() { return matchingLifecycle >= MATCHING_SUBMITTED; }

    /** Compact lifecycle label used only by the opt-in Owner wait diagnostic. */
    String matchingLifecycleName() {
        return switch (matchingLifecycle) {
            case MATCHING_ADMITTED -> "ADMITTED";
            case MATCHING_DEFERRED -> "DEFERRED";
            case MATCHING_SUBMITTED -> "SUBMITTED";
            case MATCHER_DONE -> "MATCHER_DONE";
            case LANES_DONE -> "LANES_DONE";
            case COMMITTED -> "COMMITTED";
            default -> "UNKNOWN";
        };
    }
    void matchingSubmitted() {
        // A cross-shard control path may publish its evidenced result before it releases the
        // submission cursor.  The result transition already proves submission in that case.
        if (matchingLifecycle >= MATCHER_DONE) return;
        if (isMatchingSubmitted()) throw new IllegalStateException("matching command was submitted twice");
        matchingLifecycle = MATCHING_SUBMITTED;
    }

    void settlement(com.surprising.aeron.service.state.MatcherSettlementEvent event,
                    com.surprising.aeron.service.state.MatcherSettlementPlan plan,
                    long applyStartNanos) {
        if (event == null || plan == null || hasLaneContinuation()) {
            throw new IllegalStateException("invalid matcher settlement continuation");
        }
        settlementContinuation = event;
        settlementPlan = plan;
        settlementApplyStartNanos = applyStartNanos;
    }

    /** Clears the command-owned references when its fixed sequence slot is recycled. */
    void clearReferences() {
        coreSequence = 0;
        orderBatch = null;
        operation = null;
        command = null;
        fingerprint = null;
        preMatchingCancellationOrderIds = List.of();
        fundsAccumulator.clear();
        decodedCommand = null;
        admission = null;
        settlementContinuation = null;
        placeAdmission = null;
        placeAdmissionOwnerCollected = false;
        replacementAdmission = null;
        settlementPlan = null;
        admittedPlaceOrder = null;
        admittedMatchingOrder = null;
        triggerAdmissionOrder = null;
        sourceTrigger = null;
        realtimeTakerOrder = null;
        realtimeResolvedTakerOrder = null;
        matchingLifecycle = MATCHING_ADMITTED;
        deferredClusterTimestamp = 0;
        deferredClusterPosition = 0;
        deferredSourceKey = null;
        cachedMatcherShard = -1;
        reusableResolvedOrder.clear();
    }

    void deferMatching(long clusterTimestamp, long clusterPosition, TradingCoreRuntime.SourceKey sourceKey) {
        if (matchingLifecycle != MATCHING_ADMITTED || sourceKey == null) {
            throw new IllegalStateException("invalid deferred matching transition");
        }
        deferredClusterTimestamp = clusterTimestamp;
        deferredClusterPosition = clusterPosition;
        deferredSourceKey = sourceKey;
        matchingLifecycle = MATCHING_DEFERRED;
    }
    void activateDeferredMatching() {
        if (matchingLifecycle != MATCHING_DEFERRED) {
            throw new IllegalStateException("matching command is not deferred");
        }
        matchingLifecycle = MATCHING_ADMITTED;
        deferredClusterTimestamp = 0;
        deferredClusterPosition = 0;
        deferredSourceKey = null;
    }
    boolean deferredMatching() { return matchingLifecycle == MATCHING_DEFERRED; }
    void committed() {
        matchingLifecycle = COMMITTED;
    }
    long deferredClusterTimestamp() {
        if (!deferredMatching()) throw new IllegalStateException("matching command is not deferred");
        return deferredClusterTimestamp;
    }
    long deferredClusterPosition() {
        if (!deferredMatching()) throw new IllegalStateException("matching command is not deferred");
        return deferredClusterPosition;
    }
    TradingCoreRuntime.SourceKey deferredSourceKey() {
        if (!deferredMatching()) throw new IllegalStateException("matching command is not deferred");
        return deferredSourceKey;
    }

    int cachedMatcherShard() { return cachedMatcherShard; }

    void cachedMatcherShard(int shard) {
        if (shard < 0) throw new IllegalArgumentException("matcher shard cannot be negative");
        cachedMatcherShard = shard;
    }

    void countPipelinedSettlement() { pipelinedSettlementCounted = true; }
    boolean takePipelinedSettlementCounted() {
        boolean value = pipelinedSettlementCounted;
        pipelinedSettlementCounted = false;
        return value;
    }
    boolean commitFenceEstablished() {
        return commitFenceEstablished;
    }
    long commitFenceTimestamp() {
        if (!commitFenceEstablished) throw new IllegalStateException("matching commit fence is not established");
        return commitFenceTimestamp;
    }
    long commitFenceClusterPosition() {
        if (!commitFenceEstablished) throw new IllegalStateException("matching commit fence is not established");
        return commitFenceClusterPosition;
    }

    enum Operation {
        PLACE,
        CANCEL,
        REPLACE,
        AMEND,
        TRIGGER,
        LIQUIDATION,
        LIQUIDATION_BATCH,
        SETTLEMENT
    }

    long coreSequence;
    boolean claimed;
    private long expectedLaneMask;
    private long completedLaneMask;
    private volatile CoreMatchingResult completedMatchingResult;
    private MatchingResult matchingResult;
    /** Owner claims the route; Matcher releases it before publishing the result into this slot. */
    private volatile int submittedMatcherShard = -1;

    public int submittedMatcherShard() { return submittedMatcherShard; }

    public void claimMatcherSubmission(int shardId) {
        if (!claimed || shardId < 0 || submittedMatcherShard != -1 || completedMatchingResult != null)
            throw new IllegalStateException("matcher command token is already routed or inactive");
        submittedMatcherShard = shardId;
    }

    @Override
    public void releaseMatcherSubmission(int shardId) {
        if (submittedMatcherShard != shardId) {
            throw new IllegalStateException("matcher completion belongs to another shard");
        }
        submittedMatcherShard = -1;
    }

    /**
     * Suspended commit inputs are transferred between the owner builder and this sequence
     * slot.  The slot never materializes a boxed List; the owner swaps its reusable primitive
     * buffers in and out when a continuation yields.
     */
    private PrimitiveLongChangeSet commitChangedUserIds = new PrimitiveLongChangeSet();
    private PrimitiveLongChangeSet commitChangedOrderIds = new PrimitiveLongChangeSet();
    private boolean commitContextActive;
    private final com.surprising.aeron.service.state.RuntimeFundsAccumulator commitFundsAccumulator =
            new com.surprising.aeron.service.state.RuntimeFundsAccumulator(32);
    private boolean commitSnapshotDirty;
    private boolean commitSnapshotProvisionalOnly;
    private CoreResultCode matchingRejection;

    long coreSequence() { return claimed ? coreSequence : 0; }
    long expectedLaneMask() { return expectedLaneMask; }
    long completedLaneMask() { return completedLaneMask; }
    MatchingResult matchingResult() { return matchingResult; }
    /** Final write by the Matcher: subsequent Owner work reads this slot directly. */
    public void publishMatcherResult(int shardId, CoreMatchingResult result) {
        if (result == null || result.nativeCoreSequence() != coreSequence
                || completedMatchingResult != null) {
            throw new IllegalStateException("invalid matcher result publication");
        }
        releaseMatcherSubmission(shardId);
        completedMatchingResult = result;
    }

    void publishMatchingCompletion(CoreMatchingResult result) {
        if (result == null || result.nativeCoreSequence() != coreSequence) {
            throw new IllegalStateException("invalid matching completion");
        }
        if (completedMatchingResult == null) completedMatchingResult = result;
    }

    public CoreMatchingResult takeMatchingCompletion() {
        CoreMatchingResult result = completedMatchingResult;
        // An empty probe must not erase a concurrent Matcher publication.
        if (result != null) completedMatchingResult = null;
        return result;
    }

    CoreMatchingResult matchingCompletion() { return completedMatchingResult; }
    boolean hasMatchingCompletion() { return completedMatchingResult != null; }
    void suspendCommitContext(CommandResultBuilder resultBuilder,
                              com.surprising.aeron.service.state.RuntimeFundsAccumulator fundsAccumulator,
                              boolean snapshotDirty, boolean snapshotProvisionalOnly) {
        if (resultBuilder == null || commitContextActive) {
            throw new IllegalStateException("invalid suspended sequence commit context");
        }
        PrimitiveLongChangeSet reusableUserIds = commitChangedUserIds;
        PrimitiveLongChangeSet reusableOrderIds = commitChangedOrderIds;
        commitChangedUserIds = resultBuilder.changedUserIds;
        commitChangedOrderIds = resultBuilder.changedOrderIds;
        resultBuilder.changedUserIds = reusableUserIds;
        resultBuilder.changedOrderIds = reusableOrderIds;
        commitFundsAccumulator.clear();
        if (fundsAccumulator != null) fundsAccumulator.transferToEmpty(commitFundsAccumulator);
        commitSnapshotDirty = snapshotDirty;
        commitSnapshotProvisionalOnly = snapshotProvisionalOnly;
        commitContextActive = true;
    }

    /** Restore the suspended primitive buffers and return the owner-owned buffers to the slot. */
    void restoreCommitContext(CommandResultBuilder resultBuilder) {
        requireCommit();
        if (resultBuilder == null) throw new IllegalStateException("result builder is required");
        PrimitiveLongChangeSet committedUserIds = commitChangedUserIds;
        PrimitiveLongChangeSet committedOrderIds = commitChangedOrderIds;
        commitChangedUserIds = resultBuilder.changedUserIds;
        commitChangedOrderIds = resultBuilder.changedOrderIds;
        resultBuilder.changedUserIds = committedUserIds;
        resultBuilder.changedOrderIds = committedOrderIds;
    }

    PrimitiveLongChangeSet commitChangedUserIds() { return requiredCommit(commitChangedUserIds); }
    PrimitiveLongChangeSet commitChangedOrderIds() { return requiredCommit(commitChangedOrderIds); }
    /** 恢复本序号时交还原资金缓冲，不把已汇总的逐账户 posting 再合并一次。 */
    void takeCommitFundsTo(com.surprising.aeron.service.state.RuntimeFundsAccumulator target) {
        requireCommit();
        if (target != null) { target.clear(); commitFundsAccumulator.transferToEmpty(target); }
    }
    boolean commitSnapshotDirty() { requireCommit(); return commitSnapshotDirty; }
    boolean commitSnapshotProvisionalOnly() { requireCommit(); return commitSnapshotProvisionalOnly; }

    void clearCommitContext() {
        requireCommit();
        commitChangedUserIds.clear();
        commitChangedOrderIds.clear();
        commitFundsAccumulator.clear();
        commitSnapshotDirty = false;
        commitSnapshotProvisionalOnly = false;
        commitContextActive = false;
    }

    private void requireCommit() {
        if (!commitContextActive) throw new IllegalStateException("sequence commit context is missing");
    }

    private <T> T requiredCommit(T value) {
        requireCommit();
        return value;
    }

    boolean hasCommitContext() { return commitContextActive; }

    void rejectMatching(CoreResultCode resultCode) {
        if (resultCode == null || matchingRejection != null) {
            throw new IllegalStateException("invalid matching rejection");
        }
        matchingRejection = resultCode;
    }

    boolean hasMatchingRejection() { return matchingRejection != null; }

    CoreResultCode matchingRejection() {
        if (matchingRejection == null) throw new IllegalStateException("matching rejection is missing");
        return matchingRejection;
    }

    void result(MatchingResult result, long expectedMask, long validLaneMask) {
        if (result == null || result.nativeCoreSequence() != coreSequence
                || (expectedMask & ~validLaneMask) != 0
                || matchingResult != null) {
            throw new IllegalStateException("invalid immutable matching result fanout");
        }
        matchingResult = result;
        expectedLaneMask = expectedMask;
        matchingLifecycle = MATCHER_DONE;
    }

    void resetMatchingContinuation() { completedMatchingResult = null; }

    void includeControlLanes(long laneMask, long validLaneMask) {
        if (matchingResult == null || completedLaneMask != 0 || (laneMask & ~validLaneMask) != 0) {
            throw new IllegalStateException("invalid synchronous control lane participants");
        }
        expectedLaneMask |= laneMask;
    }

    void completeLanes(long laneMask) {
        if (matchingResult == null
                || (laneMask & ~expectedLaneMask) != 0
                || (completedLaneMask & laneMask) != 0) {
            throw new IllegalStateException("duplicate or unexpected account lane completion"
                    + " laneMask=" + laneMask + " expected=" + expectedLaneMask
                    + " completed=" + completedLaneMask);
        }
        completedLaneMask |= laneMask;
        if (matchingResult != null && completedLaneMask == expectedLaneMask) {
            matchingLifecycle = LANES_DONE;
        }
    }

    boolean complete() {
        return matchingResult != null && completedLaneMask == expectedLaneMask;
    }

    void clear() {
        // CommandSlot is overwritten by initialize() before the slot can be observed
        // again.  Clearing every reference here duplicates that work on the owner hot path
        // and only reduces retention during the short free interval.
        clearLifecycleState();
    }

    private void clearLifecycleState() {
        clearMatchingControlContinuations();
        if (reusableSettlementWork != null) reusableSettlementWork.clearReferences();
        if (reusableLiquidationWork != null) reusableLiquidationWork.clearReferences();
        if (liquidationCheck != null) liquidationCheck.clearReferences();
        controlWork = null;
        controlPending = false;
        controlAccountsCommitted = false;
        controlStartedNanos = 0;
        commitEvent = null;
        resultData = null;
        claimed = false;
        expectedLaneMask = 0;
        completedLaneMask = 0;
        completedMatchingResult = null;
        matchingResult = null;
        submittedMatcherShard = -1;
        commitChangedUserIds.clear();
        commitChangedOrderIds.clear();
        commitFundsAccumulator.clear();
        commitSnapshotDirty = false;
        commitSnapshotProvisionalOnly = false;
        commitContextActive = false;
        matchingRejection = null;
        placeAdmission = null;
        replacementAdmission = null;
        matchingLifecycle = MATCHING_ADMITTED;
        deferredClusterTimestamp = 0;
        deferredClusterPosition = 0;
        deferredSourceKey = null;
        cachedMatcherShard = -1;
        laneResultTarget.clear();
    }

    private void clearMatchingControlContinuations() {
        admissionMatching.clear();
        settlementControl.clear();
        liquidationExecutionControl.clear();
        placeMatching.clear();
        cancelMatching.clear();
        replaceMatching.clear();
        controlMatching.clear();
        matcherPreCancellations = List.of();
    }

    private final class AdmissionMatchingContinuation implements java.util.function.Supplier<Object> {
        private TradingCoreRuntime owner;
        private PlaceAdmissionEvent admission;
        private int matcherShard;
        private MatcherSettlementEvent settlement;
        private java.util.function.Supplier<?> original;

        void prepare(TradingCoreRuntime owner, PlaceAdmissionEvent admission, int matcherShard,
                     MatcherSettlementEvent settlement, java.util.function.Supplier<?> original) {
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
                var place = decodedCommand.placeOrder();
                owner.matchingAdapter.rejectedPlaceDirect(
                        matcherShard, coreSequence, command.header().commandId(), place.orderId(),
                        command.header().submittedAtEpochMillis(),
                        settlement.admissionResultCode(), settlement);
                return settlement;
            }
            var cancellations = settlement.admissionCancellations();
            var place = decodedCommand.placeOrder();
            for (int index = 0; index < cancellations.size(); index++) {
                long orderId = cancellations instanceof ImmutableLongArrayList primitive
                        ? primitive.valueAt(index) : cancellations.get(index);
                var result = owner.matchingAdapter.cancelDirectPrefix(
                        command.header().submittedAtEpochMillis(), command.header().userId(),
                        orderId, place.instrumentId());
                if (result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.MATCHING_UNKNOWN_ORDER_ID) {
                    // Lane admission can precede consumption of an older maker fill. This
                    // internal intent only removes an order if it is still resting; an absent
                    // order needs neither a cancellation fact nor another account release.
                    continue;
                }
                if (result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                        || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED) {
                    settlement.recordDirectPreCancellation(orderId);
                } else {
                    return owner.matchingAdapter.publishDirectPrefixFailure(
                            matcherShard, coreSequence, command.header().commandId(), place.orderId(),
                            command.header().submittedAtEpochMillis(), result, settlement);
                }
            }
            return original.get();
        }
    }

    private final class PlaceMatchingContinuation implements java.util.function.Supplier<Object> {
        private TradingCoreRuntime owner;
        private int matcherShard;
        private long userId;
        private ResolvedPlaceOrder resolvedOrder;
        private CoreMatchingOrder matchingOrder;
        private OrderRuntime admittedOrder;
        private MatcherSettlementEvent direct;

        void prepare(TradingCoreRuntime owner, int matcherShard, long userId,
                     ResolvedPlaceOrder resolvedOrder, CoreMatchingOrder matchingOrder,
                     MatcherSettlementEvent direct) {
            prepare(owner, matcherShard, userId, resolvedOrder, matchingOrder, null, direct);
        }

        void prepare(TradingCoreRuntime owner, int matcherShard, long userId,
                     ResolvedPlaceOrder resolvedOrder, CoreMatchingOrder matchingOrder,
                     OrderRuntime admittedOrder, MatcherSettlementEvent direct) {
            if ((resolvedOrder == null ? 0 : 1) + (matchingOrder == null ? 0 : 1)
                    + (admittedOrder == null ? 0 : 1) != 1 || admittedOrder != null && direct == null) {
                throw new IllegalArgumentException("exactly one ordinary PLACE order representation is required");
            }
            this.owner = Objects.requireNonNull(owner);
            this.matcherShard = matcherShard;
            this.userId = userId;
            this.resolvedOrder = resolvedOrder;
            this.matchingOrder = matchingOrder;
            this.admittedOrder = admittedOrder;
            this.direct = Objects.requireNonNull(direct, "direct settlement");
        }

        void clear() {
            owner = null;
            matcherShard = 0;
            userId = 0;
            resolvedOrder = null;
            matchingOrder = null;
            admittedOrder = null;
            direct = null;
        }

        @Override public Object get() {
            long orderId = admittedOrder != null ? admittedOrder.orderId()
                    : resolvedOrder != null ? resolvedOrder.orderId() : matchingOrder.orderId();
            var failure = applyMatcherPreCancellations(owner, matcherShard, orderId, direct);
            if (failure != null) return failure;
            if (admittedOrder != null)
                return owner.matchingAdapter.placeAdmittedDirect(matcherShard, coreSequence, command.header().commandId(),
                        command.header().submittedAtEpochMillis(), userId, admittedOrder, direct);
            if (resolvedOrder != null)
                return owner.matchingAdapter.placeDirect(matcherShard, coreSequence, command.header().commandId(),
                        command.header().submittedAtEpochMillis(), userId, resolvedOrder, direct);
            return owner.matchingAdapter.placeDirect(matcherShard, coreSequence, command.header().commandId(),
                    command.header().submittedAtEpochMillis(), userId, matchingOrder, direct);
        }
    }

    private final class CancelMatchingContinuation implements java.util.function.Supplier<Object> {
        private TradingCoreRuntime owner;
        private int matcherShard;
        private long orderId;
        private long userId;
        private String instrumentId;
        private MatcherSettlementEvent direct;

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

        @Override public Object get() {
            var failure = applyMatcherPreCancellations(owner, matcherShard, orderId, direct);
            if (failure != null) return failure;
            return owner.matchingAdapter.cancelDirect(matcherShard, coreSequence, command.header().commandId(), orderId,
                    command.header().submittedAtEpochMillis(), userId, instrumentId, direct);
        }
    }

    private final class ReplaceMatchingContinuation implements java.util.function.Supplier<Object> {
        private TradingCoreRuntime owner;
        private int matcherShard;
        private long orderId;
        private long userId;
        private long originalOrderId;
        private String instrumentId;
        private ResolvedPlaceOrder resolvedReplacement;
        private MatcherSettlementEvent direct;

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

        @Override public Object get() {
            var failure = applyMatcherPreCancellations(owner, matcherShard, orderId, direct);
            if (failure != null) return failure;
            return owner.matchingAdapter.replaceDirect(matcherShard, coreSequence, command.header().commandId(), orderId,
                    command.header().submittedAtEpochMillis(), userId, originalOrderId, instrumentId, resolvedReplacement, direct);
        }
    }

    /** Owner-confined callback shared by all settlement polls for this fixed command slot. */
    private final class SettlementControlContinuation implements java.util.function.BooleanSupplier {
        private OrderedCommitCoordinator coordinator;
        private com.surprising.aeron.service.state.RuntimeLifecycleSettlementContinuation.SettlementWork work;

        void prepare(OrderedCommitCoordinator coordinator,
                com.surprising.aeron.service.state.RuntimeLifecycleSettlementContinuation.SettlementWork work) {
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
            coordinator.owner.resultBuilder.beginChangedUsers();
            for (long user : work.selectedUsers()) coordinator.owner.resultBuilder.addChangedUser(user);
            coordinator.owner.resultBuilder.commandSettlementProgress = work.result();
            coordinator.requestCommitPublication();
            return true;
        }
    }

    /** Owner-confined callback shared by all asynchronous liquidation execution polls. */
    private final class LiquidationExecutionContinuation implements java.util.function.BooleanSupplier {
        private OrderedCommitCoordinator coordinator;
        private com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor.ExecutionWork work;
        private com.surprising.aeron.protocol.CoreLiquidationProgressView progress;

        void prepare(OrderedCommitCoordinator coordinator,
                com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor.ExecutionWork work,
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
            coordinator.owner.resultBuilder.commandLiquidationProgress = progress;
            coordinator.requestCommitPublication();
            return true;
        }
    }

    static final class LaneResultTarget
            implements com.surprising.aeron.service.state.LaneOrderResultTarget {
        private final long[] ids = new long[2];
        private final OrderRuntime[] orders = new OrderRuntime[2];
        private final OrderRuntime[] orderCopies = new OrderRuntime[2];
        private final String[] symbols = new String[2];
        private int count;
        private int capturedMask;
        private boolean prepared;
        private byte[] response;
        private int responseLength;
        private ResponseArena responseArena;
        private ResponseArena.Slot responseSlot;

        void bind(long first, long second, ResponseArena responseArena) {
            clear();
            this.responseArena = responseArena;
            if (first <= 0) return;
            ids[0] = first;
            count = 1;
            if (second > 0 && second != first) {
                ids[1] = second;
                count = 2;
            }
        }

        boolean active() { return count != 0; }
        boolean prepared() { return prepared; }
        OrderRuntime order(long id) {
            for (int index = 0; index < count; index++) if (ids[index] == id) return orders[index];
            return null;
        }
        String instrumentId(long id) {
            for (int index = 0; index < count; index++) if (ids[index] == id) return symbols[index];
            return null;
        }

        @Override public int resultCount() { return count; }
        @Override public boolean captureUnchangedResults() { return true; }
        @Override public boolean resultPrepared(int index) { return (capturedMask & (1 << index)) != 0; }
        @Override public long resultOrderId(int index) { return ids[index]; }
        @Override public long resultOriginalOrderId(int index) { return 0; }
        @Override public void resultOrder(int index, OrderRuntime order, String instrumentId) {
            orders[index] = order == null ? null : (orderCopies[index] = order.copyIntoCommandSlot(orderCopies[index]));
            symbols[index] = instrumentId;
            capturedMask |= 1 << index;
        }
        @Override public boolean includeTerminalAfterImage() { return true; }
        @Override public void prepareResponse() {
            if (responseSlot != null) responseArena.release(responseSlot);
            responseSlot = null;
            prepared = false;
            response = null;
            responseLength = 0;
            if (responseOrders.isEmpty()) {
                response = TradingCoreRuntime.EMPTY_RESPONSE_DATA;
                prepared = true;
                return;
            }
            try {
                boolean single = responseOrders.size() == 1;
                var first = responseOrders.get(0);
                int length = single
                        ? com.surprising.aeron.protocol.CoreCommandResultCodec.encodedSingleOrderLength(first)
                        : com.surprising.aeron.protocol.CoreCommandResultCodec.encodedLength(responseOrders, java.util.List.of());
                responseSlot = responseArena.acquireSlot(length);
                int encodedLength = single
                        ? com.surprising.aeron.protocol.CoreCommandResultCodec.encodeSingleOrderInto(first, responseSlot.writer())
                        : com.surprising.aeron.protocol.CoreCommandResultCodec.encodeInto(
                                responseOrders, java.util.List.of(), responseSlot.storage, 0);
                responseSlot.length = encodedLength;
                responseLength = encodedLength;
                response = responseSlot.storage;
                prepared = true;
            } catch (RuntimeException | Error failure) {
                if (responseSlot != null) responseArena.release(responseSlot);
                responseSlot = null;
                throw failure;
            }
        }

        private final OrderSource[] sources = {new OrderSource(0), new OrderSource(1)};
        private final java.util.List<com.surprising.aeron.protocol.CoreOrderStateSource> responseOrders = new OrderSources();

        private final class OrderSources extends java.util.AbstractList<com.surprising.aeron.protocol.CoreOrderStateSource>
                implements java.util.RandomAccess {
            @Override public int size() {
                int size = 0;
                for (int i = 0; i < count; i++) if (orders[i] != null) size++;
                return size;
            }
            @Override public com.surprising.aeron.protocol.CoreOrderStateSource get(int index) {
                if (index < 0) throw new IndexOutOfBoundsException(index);
                for (int i = 0; i < count; i++) {
                    if (orders[i] != null && index-- == 0) return sources[i];
                }
                throw new IndexOutOfBoundsException(index);
            }
        }

        private final class OrderSource implements com.surprising.aeron.protocol.CoreOrderStateSource {
            private final int index;
            private OrderSource(int index) { this.index = index; }
            private OrderRuntime value() { return orders[index]; }
            public long orderId() { return value().orderId(); }
            public com.surprising.product.api.ProductLine productLine() { return value().productLine(); }
            public long userId() { return value().userId(); }
            public String instrumentId() { return symbols[index]; }
            public com.surprising.aeron.protocol.CoreOrderSide side() { return value().side(); }
            public long priceTicks() { return value().priceTicks(); }
            public long quantitySteps() { return value().quantitySteps(); }
            public long executedQuantitySteps() { return value().executedQuantitySteps(); }
            public long remainingQuantitySteps() { return value().remainingQuantitySteps(); }
            public boolean reduceOnly() { return value().reduceOnly(); }
            public com.surprising.aeron.protocol.CoreMarginMode marginMode() { return value().marginMode(); }
            public com.surprising.aeron.protocol.CorePositionSide positionSide() { return value().positionSide(); }
            public com.surprising.aeron.protocol.CoreOrderType orderType() { return value().orderType(); }
            public com.surprising.aeron.protocol.CoreTimeInForce timeInForce() { return value().timeInForce(); }
            public boolean postOnly() { return value().postOnly(); }
            public String clientOrderId() { return value().clientOrderId(); }
            public java.util.UUID commandId() { return value().commandId(); }
            public long makerFeeRatePpm() { return value().makerFeeRatePpm(); }
            public long takerFeeRatePpm() { return value().takerFeeRatePpm(); }
            public long cumulativeFeeUnits() { return value().cumulativeFeeUnits(); }
            public long executedValueHigh() { return value().executedValueHigh(); }
            public long executedValueLow() { return value().executedValueLow(); }
            public long createdAtEpochMillis() { return value().createdAtEpochMillis(); }
            public long updatedAtEpochMillis() { return value().updatedAtEpochMillis(); }
            public long clusterPosition() { return value().clusterPosition(); }
            public String status() { return value().status().name(); }
            public long revision() { return value().revision(); }
        }

        @Override public byte[] preparedResponse() { return response; }
        @Override public int preparedResponseLength() { return responseLength; }

        void transferResponseOwnership() {
            responseSlot = null;
            response = null;
            responseLength = 0;
        }

        void clear() {
            if (responseSlot != null && responseArena != null) responseArena.release(responseSlot);
            java.util.Arrays.fill(orders, null);
            for (OrderRuntime copy : orderCopies) if (copy != null) copy.clearCommandSlot();
            java.util.Arrays.fill(symbols, null);
            java.util.Arrays.fill(ids, 0);
            count = 0;
            capturedMask = 0;
            prepared = false;
            response = null;
            responseLength = 0;
            responseSlot = null;
        }
    }
}
