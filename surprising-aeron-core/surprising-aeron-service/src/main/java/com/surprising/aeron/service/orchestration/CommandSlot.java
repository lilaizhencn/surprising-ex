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
import com.surprising.aeron.service.state.LaneCancelEvent;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.MatcherSettlementEvent;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
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
    /** Exactly one settlement/cancel continuation can own a command at a time. */
    private ContinuationKind continuationKind;
    private com.surprising.aeron.service.state.MatcherSettlementEvent settlementContinuation;
    private LaneCancelEvent cancelContinuation;
    /** Normal PLACE admission runs alongside the settlement continuation. */
    private PlaceAdmissionEvent placeAdmission;
    private boolean placeAdmissionOwnerCollected;
    private com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan;
    private long settlementApplyStartNanos;
    /** 普通 PLACE 已在 Owner 解析，Lane 完成后直接复用该值，避免撮合命令副本。 */
    private ResolvedPlaceOrder admittedPlaceOrder;
    private CoreMatchingOrder admittedMatchingOrder;
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
    /** 准入时固定的潜在成交账户分区，整个命令终态前保持不变。 */
    long partitionLaneMask;
    /** 派发前的不可变订单身份；仅实时推送启用时保留，提交后释放。 */
    com.surprising.aeron.service.state.OrderRuntime realtimeTakerOrder;
    /** Normal PLACE reuses its immutable resolved input for realtime trade identity. */
    ResolvedPlaceOrder realtimeResolvedTakerOrder;
    private boolean dispatchOnly;
    private boolean pipelinedSettlementCounted;
    /** Cached matcher shard for a non-batch command; stable until this slot is recycled. */
    private int cachedMatcherShard = -1;

    // Matching controls reuse the same continuation fields as settlement/clearing work.
    java.util.function.BooleanSupplier controlWork;
    /** Slot-owned settlement continuation; retained across reuse to avoid per-command lambdas. */
    private com.surprising.aeron.service.state.RuntimeLifecycleSettlementContinuation.SettlementWork reusableSettlementWork;
    private final SettlementControlContinuation settlementControl = new SettlementControlContinuation();
    private com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor.ExecutionWork reusableLiquidationWork;
    private final LiquidationExecutionContinuation liquidationExecutionControl =
            new LiquidationExecutionContinuation();
    /** Fixed wrapper for the only admission-gated Matcher submission. */
    private final AdmissionMatchingContinuation admissionMatching = new AdmissionMatchingContinuation();
    /** Fixed wrapper for the ordinary PLACE Matcher submission. */
    private final PlaceMatchingContinuation placeMatching = new PlaceMatchingContinuation();
    /** Fixed wrapper for the ordinary CANCEL Matcher submission. */
    private final CancelMatchingContinuation cancelMatching = new CancelMatchingContinuation();
    /** Fixed wrapper for a resolved ordinary REPLACE/AMEND Matcher submission. */
    private final ReplaceMatchingContinuation replaceMatching = new ReplaceMatchingContinuation();
    com.surprising.aeron.service.state.LaneCommitEvent commitEvent;

    boolean controlPending;
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
    String laneResultSymbol(long orderId) { return laneResultTarget.symbol(orderId); }
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

    /** Reuses the command slot for the common cancellation path instead of allocating a lambda. */
    java.util.function.Supplier<?> prepareCancelMatching(TradingCoreRuntime owner,
            int matcherShard, long orderId, long userId, String symbol, MatcherSettlementEvent direct) {
        cancelMatching.prepare(owner, matcherShard, orderId, userId, symbol, direct);
        return cancelMatching;
    }

    /** Reuses the command slot for a resolved replacement without intermediate submission objects. */
    java.util.function.Supplier<?> prepareReplaceMatching(TradingCoreRuntime owner,
            int matcherShard, long orderId, long userId,
            long originalOrderId, String symbol, CoreMatchingOrder replacement,
            MatcherSettlementEvent direct) {
        replaceMatching.prepare(owner, matcherShard, orderId, userId,
                originalOrderId, symbol, replacement, direct);
        return replaceMatching;
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
        continuationKind = null;
        settlementContinuation = null;
        cancelContinuation = null;
        placeAdmission = null;
        placeAdmissionOwnerCollected = false;
        settlementPlan = null;
        settlementApplyStartNanos = 0;
        admittedMatchingOrder = null;
        admittedPlaceOrder = null;
        matchingLifecycle = MATCHING_ADMITTED;
        deferredClusterTimestamp = 0;
        deferredClusterPosition = 0;
        deferredSourceKey = null;
        crossShardCancellationStarted = false;
        clusterIndependent = false;
        partitionLaneMask = 0;
        realtimeTakerOrder = null;
        realtimeResolvedTakerOrder = null;
        dispatchOnly = false;
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
        if (sourceFunds == null) throw new IllegalArgumentException("pending command funds are required");
        initialize(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds,
                RuntimeFundsDelta.empty(), decodedCommand, admission);
        sourceFunds.transferToEmpty(fundsAccumulator);
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
    com.surprising.aeron.service.state.MatcherSettlementEvent settlementEvent() {
        return continuationKind == ContinuationKind.SETTLEMENT ? settlementContinuation : null;
    }
    com.surprising.aeron.service.state.MatcherSettlementEvent takeSettlementEvent() {
        var value = settlementEvent();
        clearContinuation(ContinuationKind.SETTLEMENT);
        return value;
    }
    LaneCancelEvent cancelEvent() {
        return continuationKind == ContinuationKind.CANCEL ? cancelContinuation : null;
    }
    LaneCancelEvent takeCancelEvent() {
        LaneCancelEvent value = cancelEvent();
        clearContinuation(ContinuationKind.CANCEL);
        return value;
    }
    com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan() { return settlementPlan; }
    long settlementApplyStartNanos() { return settlementApplyStartNanos; }
    PlaceAdmissionEvent placeAdmission() {
        return placeAdmission;
    }
    boolean hasLaneContinuation() {
        return settlementContinuation != null || cancelContinuation != null;
    }
    boolean laneContinuationComplete() {
        if (!hasLaneContinuation()) return false;
        return switch (continuationKind) {
            case SETTLEMENT -> settlementEvent().complete();
            case CANCEL -> cancelEvent().complete();
            default -> false;
        };
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
    void triggerAdmission(CoreMatchingOrder order) {
        if (operation != Operation.TRIGGER || order == null || admittedMatchingOrder != null)
            throw new IllegalStateException("invalid trigger admission");
        admittedMatchingOrder = order;
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
    void cancel(LaneCancelEvent event, long applyStartNanos) {
        if (event == null || hasLaneContinuation()
                || operation != Operation.CANCEL) {
            throw new IllegalStateException("invalid cancel continuation");
        }
        continuationKind = ContinuationKind.CANCEL;
        cancelContinuation = event;
        settlementApplyStartNanos = applyStartNanos;
    }
    void settlement(com.surprising.aeron.service.state.MatcherSettlementEvent event,
                    com.surprising.aeron.service.state.MatcherSettlementPlan plan,
                    long applyStartNanos) {
        if (event == null || plan == null || hasLaneContinuation()) {
            throw new IllegalStateException("invalid matcher settlement continuation");
        }
        continuationKind = ContinuationKind.SETTLEMENT;
        settlementContinuation = event;
        settlementPlan = plan;
        settlementApplyStartNanos = applyStartNanos;
    }

    private void clearContinuation(ContinuationKind expected) {
        if (continuationKind != expected) return;
        continuationKind = null;
        if (expected == ContinuationKind.SETTLEMENT) settlementContinuation = null;
        else if (expected == ContinuationKind.CANCEL) cancelContinuation = null;
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
        continuationKind = null;
        settlementContinuation = null;
        cancelContinuation = null;
        placeAdmission = null;
        placeAdmissionOwnerCollected = false;
        settlementPlan = null;
        admittedPlaceOrder = null;
        admittedMatchingOrder = null;
        realtimeTakerOrder = null;
        realtimeResolvedTakerOrder = null;
        matchingLifecycle = MATCHING_ADMITTED;
        deferredClusterTimestamp = 0;
        deferredClusterPosition = 0;
        deferredSourceKey = null;
        cachedMatcherShard = -1;
    }
    void dispatchOnly() { dispatchOnly = true; }
    boolean isDispatchOnly() { return dispatchOnly; }

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
    boolean takeDispatchOnly() {
        boolean value = dispatchOnly;
        dispatchOnly = false;
        return value;
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

    private enum ContinuationKind {
        SETTLEMENT,
        CANCEL
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
    /** 结算计划由序号槽独占；终态结果消费后清理引用并保留容量。 */
    private com.surprising.aeron.service.state.MatcherSettlementPlan reusableSettlementPlan;
    com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlanBuffer() {
        if (!claimed) throw new IllegalStateException("unclaimed settlement slot");
        if (reusableSettlementPlan == null)
            reusableSettlementPlan = new com.surprising.aeron.service.state.MatcherSettlementPlan();
        return reusableSettlementPlan;
    }
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
        if (resultBuilder == null || fundsAccumulator == null || commitContextActive) {
            throw new IllegalStateException("invalid suspended sequence commit context");
        }
        PrimitiveLongChangeSet reusableUserIds = commitChangedUserIds;
        PrimitiveLongChangeSet reusableOrderIds = commitChangedOrderIds;
        commitChangedUserIds = resultBuilder.changedUserIds;
        commitChangedOrderIds = resultBuilder.changedOrderIds;
        resultBuilder.changedUserIds = reusableUserIds;
        resultBuilder.changedOrderIds = reusableOrderIds;
        commitFundsAccumulator.clear();
        fundsAccumulator.transferToEmpty(commitFundsAccumulator);
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
        target.clear();
        commitFundsAccumulator.transferToEmpty(target);
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
        if (reusableSettlementPlan != null) reusableSettlementPlan.clearReferences();
        // CommandSlot is overwritten by initialize() before the slot can be observed
        // again.  Clearing every reference here duplicates that work on the owner hot path
        // and only reduces retention during the short free interval.
        clearLifecycleState();
    }

    private void clearLifecycleState() {
        clearMatchingControlContinuations();
        if (reusableSettlementWork != null) reusableSettlementWork.clearReferences();
        if (reusableLiquidationWork != null) reusableLiquidationWork.clearReferences();
        controlWork = null;
        controlPending = false;
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
            return original.get();
        }
    }

    private final class PlaceMatchingContinuation implements java.util.function.Supplier<Object> {
        private TradingCoreRuntime owner;
        private int matcherShard;
        private long userId;
        private ResolvedPlaceOrder resolvedOrder;
        private CoreMatchingOrder matchingOrder;
        private MatcherSettlementEvent direct;

        void prepare(TradingCoreRuntime owner, int matcherShard, long userId,
                     ResolvedPlaceOrder resolvedOrder, CoreMatchingOrder matchingOrder,
                     MatcherSettlementEvent direct) {
            if ((resolvedOrder == null) == (matchingOrder == null)) {
                throw new IllegalArgumentException("exactly one ordinary PLACE order representation is required");
            }
            this.owner = Objects.requireNonNull(owner);
            this.matcherShard = matcherShard;
            this.userId = userId;
            this.resolvedOrder = resolvedOrder;
            this.matchingOrder = matchingOrder;
            this.direct = direct;
        }

        void clear() {
            owner = null;
            matcherShard = 0;
            userId = 0;
            resolvedOrder = null;
            matchingOrder = null;
            direct = null;
        }

        @Override
        public Object get() {
            if (direct != null) {
                if (resolvedOrder != null) {
                    return owner.matchingAdapter.placeDirect(
                            matcherShard, coreSequence, command.header().commandId(),
                            command.header().submittedAtEpochMillis(), userId, resolvedOrder, direct);
                }
                return owner.matchingAdapter.placeDirect(
                        matcherShard, coreSequence, command.header().commandId(),
                        command.header().submittedAtEpochMillis(), userId, matchingOrder, direct);
            }
            if (resolvedOrder != null) {
                return owner.matchingAdapter.placeWithEvidence(
                        matcherShard, coreSequence, command.header().commandId(),
                        command.header().submittedAtEpochMillis(), userId, resolvedOrder);
            }
            return owner.matchingAdapter.placeWithEvidence(
                    matcherShard, coreSequence, command.header().commandId(),
                    command.header().submittedAtEpochMillis(), userId, matchingOrder);
        }
    }

    private final class CancelMatchingContinuation implements java.util.function.Supplier<Object> {
        private TradingCoreRuntime owner;
        private int matcherShard;
        private long orderId;
        private long userId;
        private String symbol;
        private MatcherSettlementEvent direct;

        void prepare(TradingCoreRuntime owner, int matcherShard, long orderId,
                     long userId, String symbol, MatcherSettlementEvent direct) {
            this.owner = Objects.requireNonNull(owner);
            this.matcherShard = matcherShard;
            this.orderId = orderId;
            this.userId = userId;
            this.symbol = Objects.requireNonNull(symbol);
            this.direct = direct;
        }

        void clear() {
            owner = null;
            matcherShard = 0;
            orderId = 0;
            userId = 0;
            symbol = null;
            direct = null;
        }

        @Override
        public Object get() {
            if (direct != null) {
                return owner.matchingAdapter.cancelDirect(
                        matcherShard, coreSequence, command.header().commandId(), orderId,
                        command.header().submittedAtEpochMillis(), userId, symbol, direct);
            }
            return owner.matchingAdapter.cancelWithEvidence(
                    matcherShard, coreSequence, command.header().commandId(), orderId,
                    command.header().submittedAtEpochMillis(), userId, symbol);
        }
    }

    private final class ReplaceMatchingContinuation implements java.util.function.Supplier<Object> {
        private TradingCoreRuntime owner;
        private int matcherShard;
        private long orderId;
        private long userId;
        private long originalOrderId;
        private String symbol;
        private CoreMatchingOrder replacement;
        private MatcherSettlementEvent direct;

        void prepare(TradingCoreRuntime owner, int matcherShard, long orderId,
                     long userId, long originalOrderId, String symbol, CoreMatchingOrder replacement,
                     MatcherSettlementEvent direct) {
            this.owner = Objects.requireNonNull(owner);
            this.matcherShard = matcherShard;
            this.orderId = orderId;
            this.userId = userId;
            this.originalOrderId = originalOrderId;
            this.symbol = Objects.requireNonNull(symbol);
            this.replacement = Objects.requireNonNull(replacement);
            this.direct = direct;
        }

        void clear() {
            owner = null;
            matcherShard = 0;
            orderId = 0;
            userId = 0;
            originalOrderId = 0;
            symbol = null;
            replacement = null;
            direct = null;
        }

        @Override
        public Object get() {
            if (direct != null) {
                return owner.matchingAdapter.replaceDirect(
                        matcherShard, coreSequence, command.header().commandId(), orderId,
                        command.header().submittedAtEpochMillis(), userId,
                        originalOrderId, symbol, replacement, direct);
            }
            return owner.matchingAdapter.replaceWithEvidence(
                    matcherShard, coreSequence, command.header().commandId(), orderId,
                    command.header().submittedAtEpochMillis(), userId,
                    originalOrderId, symbol, replacement);
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

    private static final class LaneResultTarget
            implements com.surprising.aeron.service.state.LaneOrderResultTarget {
        private final long[] ids = new long[2];
        private final OrderRuntime[] orders = new OrderRuntime[2];
        private final String[] symbols = new String[2];
        private int count;
        private boolean prepared;
        private com.surprising.aeron.service.matching.MatchingResult matcherResult;
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
        String symbol(long id) {
            for (int index = 0; index < count; index++) if (ids[index] == id) return symbols[index];
            return null;
        }

        @Override public int resultCount() { return count; }
        @Override public long resultOrderId(int index) { return ids[index]; }
        @Override public long resultOriginalOrderId(int index) { return 0; }
        @Override public void resultOrder(int index, OrderRuntime order, String symbol) {
            orders[index] = order;
            symbols[index] = symbol;
        }
        @Override public boolean includeTerminalAfterImage() { return true; }
        @Override public void matcherResult(com.surprising.aeron.service.matching.MatchingResult result) {
            matcherResult = result;
        }
        @Override public void prepareResponse() {
            prepared = true;
            if (count != 1 || orders[0] == null || matcherResult == null) return;
            try {
                int length = com.surprising.aeron.protocol.CoreCommandResultCodec
                        .encodedSingleOrderLength(source);
                responseSlot = responseArena.acquireSlot(length);
                response = responseSlot.storage;
                responseLength = com.surprising.aeron.protocol.CoreCommandResultCodec
                        .encodeSingleOrderInto(source, response, 0);
                responseSlot.length = responseLength;
            } catch (IllegalArgumentException ignored) {
                if (responseSlot != null) responseArena.release(responseSlot);
                responseSlot = null;
                response = null;
                responseLength = 0;
            }
        }

        private final com.surprising.aeron.protocol.CoreOrderStateSource source =
                new com.surprising.aeron.protocol.CoreOrderStateSource() {
                    private OrderRuntime value() { return orders[0]; }
                    public long orderId() { return value().orderId(); }
                    public com.surprising.product.api.ProductLine productLine() { return value().productLine(); }
                    public long userId() { return value().userId(); }
                    public String symbol() { return symbols[0]; }
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
                    public long createdAtEpochMillis() { return value().createdAtEpochMillis(); }
                    public long updatedAtEpochMillis() { return value().updatedAtEpochMillis(); }
                    public long clusterPosition() { return value().clusterPosition(); }
                    public String status() { return value().status().name(); }
                    public long revision() { return value().revision(); }
                };

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
            java.util.Arrays.fill(symbols, null);
            java.util.Arrays.fill(ids, 0);
            count = 0;
            prepared = false;
            matcherResult = null;
            response = null;
            responseLength = 0;
            responseSlot = null;
        }
    }
}
