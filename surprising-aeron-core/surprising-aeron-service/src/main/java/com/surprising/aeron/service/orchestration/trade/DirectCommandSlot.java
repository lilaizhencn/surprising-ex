package com.surprising.aeron.service.orchestration.trade;

import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.service.command.trigger.TriggerOrderCommands;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.aeron.service.orchestration.retention.TerminalStateRetention;
import com.surprising.aeron.service.state.account.AccountControlTask;
import com.surprising.aeron.service.state.lifecycle.RuntimePerpetualFundingProcessor;
import com.surprising.aeron.service.state.order.RuntimeAlgoOrderStateTransitions;
import com.surprising.aeron.service.state.order.RuntimeTriggerOrderStateTransitions;
import com.surprising.aeron.service.state.risk.RiskScanCoordinator;
import com.surprising.aeron.service.state.adl.RuntimeAdlExecution;
import com.surprising.aeron.service.state.liquidation.RuntimeLiquidationResolution;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/**
 * The single reusable slot for a direct control command.
 *
 * <p>This slot is deliberately separate from {@link CommandSlot}: a direct command has no
 * matcher sequence or order-book completion, but it may wait for an Account Lane continuation.
 * It owns only that one-command continuation and its reusable low-frequency work objects. The
 * runtime remains the owner of business state, commit publication and rollback.</p>
 */
public final class DirectCommandSlot {
    private CoreMessage command;
    private CommandFingerprint fingerprint;
    private TradingCoreRuntime.SourceKey sourceKey;
    private long commitFenceTimestamp;
    private long commitFenceClusterPosition;

    private boolean active;
    private long beforeRevision;
    private long checkpoint;
    private long identityCheckpoint;
    private BooleanSupplier controlWork;
    private ResponseStatus status;
    private CoreResultCode resultCode;
    private com.surprising.aeron.service.state.change.LaneCommitEvent commitEvent;
    private boolean finalizationPrepared;
    private boolean publicationComplete;
    private boolean fundsPreflight, fundsRetainPrepared;
    private long fundsLastSourceSequence;
    private final TerminalStateRetention.FundsIndexWork fundsIndexWork = new TerminalStateRetention.FundsIndexWork();

    private final FundingControlContinuation fundingControl = new FundingControlContinuation();
    private final RiskScanControlContinuation riskScanControl = new RiskScanControlContinuation();
    private final AdlControlContinuation adlControl = new AdlControlContinuation();
    private final LiquidationResolutionControlContinuation liquidationResolutionControl =
            new LiquidationResolutionControlContinuation();
    private final AccountControlTask accountControl = new AccountControlTask();
    private TradingCoreRuntime ownerRef;
    private final BooleanSupplier accountControlWork = () -> {
        if (!accountControl.poll()) return false;
        if (accountControl.stateChanged()) ownerRef.requestCommitPublication();
        return true;
    };
    private final TriggerControlContinuation triggerControl = new TriggerControlContinuation();
    private static final BooleanSupplier COMPLETE_CONTROL = () -> true;

    private RuntimePerpetualFundingProcessor.FundingWork reusableFundingWork;
    private RuntimeAdlExecution.AdlWork reusableAdlWork;
    private RuntimeLiquidationResolution.ResolutionWork reusableResolutionWork;
    private RiskScanCoordinator reusableRiskCoordinator;
    private com.surprising.aeron.service.state.ledger.RuntimeInsuranceFundStateTransitions.AdjustmentWork reusableInsuranceAdjustment;

    public boolean active() { return active; }

    public void initialize(CoreMessage command, CommandFingerprint fingerprint,
            TradingCoreRuntime.SourceKey sourceKey, long timestamp, long position,
            long beforeRevision,
            long checkpoint, long identityCheckpoint) {
        if (active) throw new IllegalStateException("direct command slot is occupied");
        this.command = Objects.requireNonNull(command);
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.sourceKey = Objects.requireNonNull(sourceKey);
        this.beforeRevision = beforeRevision;
        this.checkpoint = checkpoint;
        this.identityCheckpoint = identityCheckpoint;
        commitFenceTimestamp = timestamp;
        commitFenceClusterPosition = position;
        active = true;
    }

    public boolean hasControlWork() { return controlWork != null; }
    public BooleanSupplier controlWork() { return controlWork; }
    public void replaceControlWork(BooleanSupplier work) { controlWork = Objects.requireNonNull(work); }
    CoreMessage command() { return command; }
    ResponseStatus status() { return status; }
    CoreResultCode resultCode() { return resultCode; }
    public void result(ResponseStatus status, CoreResultCode resultCode) {
        this.status = status;
        this.resultCode = resultCode;
    }
    public boolean finalizationPrepared() { return finalizationPrepared; }
    public boolean publicationComplete() { return publicationComplete; }
    public void markPublicationComplete() { publicationComplete = true; }
    public void markFinalizationPrepared() { finalizationPrepared = true; }
    public void clearFinalizationPrepared() { finalizationPrepared = false; }

    public void beginFundsCheck(CoreMessage message, CommandFingerprint fingerprint,
            TradingCoreRuntime.SourceKey sourceKey, long timestamp, long position, long lastSourceSequence,
            TerminalStateRetention retention) {
        // No business scope or before-image is acquired until the exact identity check passes.
        initialize(message, fingerprint, sourceKey, timestamp, position, 0, 0, 0);
        fundsPreflight = true;
        fundsLastSourceSequence = lastSourceSequence;
        fundsIndexWork.prepareLookup(retention, message.header().commandIdMsb(), message.header().commandIdLsb());
    }

    public boolean retainFundsOnLedger(TradingCoreRuntime owner) {
        if (!fundsRetainPrepared) {
            fundsIndexWork.prepareWrite(owner.terminalRetention(), command.header().commandIdMsb(),
                    command.header().commandIdLsb(), fingerprint);
            fundsRetainPrepared = true;
        }
        return fundsIndexWork.poll(owner);
    }

    private CoreResponse pollFundsCheck(TradingCoreRuntime owner) {
        if (!fundsIndexWork.poll(owner)) return null;
        CoreResponse replay = owner.fundsReplayResponse(command, fingerprint, fundsIndexWork.retained());
        if (replay == null) replay = owner.checkSourceSequence(command, fundsLastSourceSequence);
        CoreMessage checkedCommand = command;
        CommandFingerprint checkedFingerprint = fingerprint;
        TradingCoreRuntime.SourceKey checkedSource = sourceKey;
        long timestamp = commitFenceTimestamp, position = commitFenceClusterPosition;
        clear();
        return replay != null ? replay : owner.applyDirectCommand(
                checkedCommand, timestamp, position, checkedSource, checkedFingerprint);
    }
    public com.surprising.aeron.service.state.change.LaneCommitEvent commitEvent() { return commitEvent; }
    public void commitEvent(com.surprising.aeron.service.state.change.LaneCommitEvent event) { commitEvent = event; }
    public void clearCommitEvent() { commitEvent = null; }
    long commitFenceTimestamp() { return commitFenceTimestamp; }
    long checkpoint() { return checkpoint; }

    public void deferControl(BooleanSupplier work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        controlWork = Objects.requireNonNull(work);
    }

    public void deferFundingControl(TradingCoreRuntime owner,
            RuntimePerpetualFundingProcessor.FundingWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        fundingControl.prepare(owner, work);
        reusableFundingWork = work;
        controlWork = fundingControl;
    }

    public RuntimePerpetualFundingProcessor.FundingWork reusableFundingWork() { return reusableFundingWork; }

    public void deferRiskScanControl(TradingCoreRuntime owner, RiskScanCoordinator risk, int symbolId,
            String instrumentId, int maxUsers, int pendingBefore, long startedAt, long beforeRevision) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        riskScanControl.prepare(owner, risk, symbolId, instrumentId, maxUsers, pendingBefore, startedAt,
                beforeRevision);
        controlWork = riskScanControl;
    }

    public void deferAdlControl(TradingCoreRuntime owner,
            RuntimeAdlExecution.AdlWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        adlControl.prepare(owner, work);
        reusableAdlWork = work;
        controlWork = adlControl;
    }

    public RuntimeAdlExecution.AdlWork reusableAdlWork() { return reusableAdlWork; }

    public void deferLiquidationResolutionControl(TradingCoreRuntime owner,
            RuntimeLiquidationResolution.ResolutionWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        liquidationResolutionControl.prepare(owner, work);
        reusableResolutionWork = work;
        controlWork = liquidationResolutionControl;
    }

    public RuntimeLiquidationResolution.ResolutionWork reusableLiquidationResolutionWork() {
        return reusableResolutionWork;
    }

    public RiskScanCoordinator reusableRiskScanCoordinator(int maxUsers,
            com.surprising.aeron.service.state.index.PositionUserIndex positionUserIndex,
            com.surprising.aeron.service.state.TradingRuntimeState runtime,
            com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry identities) {
        if (reusableRiskCoordinator == null) {
            reusableRiskCoordinator = new RiskScanCoordinator(maxUsers, positionUserIndex, runtime, identities);
        } else {
            reusableRiskCoordinator.resetForCommand(maxUsers);
        }
        return reusableRiskCoordinator;
    }

    public com.surprising.aeron.service.state.ledger.RuntimeInsuranceFundStateTransitions.AdjustmentWork reusableInsuranceAdjustment() {
        return reusableInsuranceAdjustment;
    }

    public void rememberInsuranceAdjustment(com.surprising.aeron.service.state.ledger.RuntimeInsuranceFundStateTransitions.AdjustmentWork work) {
        reusableInsuranceAdjustment = work;
    }

    public void deferBalanceAdjustment(TradingCoreRuntime owner, long userId, BalanceAdjustmentCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        this.ownerRef = owner;
        accountControl.prepareBalanceAdjustment(owner.runtimeState(), owner.identities(), userId, command);
        controlWork = accountControlWork;
    }

    public void deferTransferOut(TradingCoreRuntime owner, long userId, TransferFundsCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        this.ownerRef = owner;
        accountControl.prepareTransferOut(owner.runtimeState(), owner.identities(), userId, command);
        controlWork = accountControlWork;
    }

    public void deferTransferComplete(TradingCoreRuntime owner, long userId, long transferId) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        this.ownerRef = owner;
        accountControl.prepareTransferCompletion(owner.runtimeState(), userId, transferId);
        controlWork = accountControlWork;
    }

    public void deferLeverageChange(TradingCoreRuntime owner, long userId, UpdateLeverageCommand command,
                             long beforeRevision) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        this.ownerRef = owner;
        accountControl.prepareLeverageChange(owner.runtimeState(), owner.identities(), userId, command, beforeRevision);
        controlWork = accountControlWork;
    }

    public void deferPositionModeChange(TradingCoreRuntime owner, long userId, UpdatePositionModeCommand command,
                                 long beforeRevision) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        this.ownerRef = owner;
        accountControl.preparePositionModeChange(owner.runtimeState(), userId, command, beforeRevision);
        controlWork = accountControlWork;
    }

    public void deferPositionMarginAdjustment(TradingCoreRuntime owner, long userId,
                                       AdjustPositionMarginCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        this.ownerRef = owner;
        accountControl.preparePositionMarginAdjustment(owner.runtimeState(), owner.identities(), userId, command);
        controlWork = accountControlWork;
    }

    public void deferTriggerMutationControl(TradingCoreRuntime owner, long userId,
            TriggerOrderCommands.Mutation mutation, long triggerOrderId, long arg1, long arg2,
            long arg3, boolean flag, String text) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareMutation(owner, userId, mutation, triggerOrderId, arg1, arg2, arg3,
                flag, text);
        owner.runtimeState().dispatchControlLanes(
                1L << owner.runtimeState().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    public void deferTriggerUpsertControl(TradingCoreRuntime owner, long userId,
            CoreTriggerOrderStateView trigger, int symbolId, long positionKey,
            boolean instrumentSettled) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareTriggerUpsert(owner, userId, trigger, symbolId, positionKey,
                instrumentSettled);
        owner.runtimeState().dispatchControlLanes(
                1L << owner.runtimeState().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    public void deferTriggerOcoPairControl(TradingCoreRuntime owner, long userId,
            CoreTriggerOrderStateView first, CoreTriggerOrderStateView second,
            int symbolId, long positionKey, boolean instrumentSettled) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareTriggerUpsert(owner, userId, first, symbolId, positionKey, instrumentSettled);
        triggerControl.secondTrigger = second;
        owner.runtimeState().dispatchControlLanes(
                1L << owner.runtimeState().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    public void deferAlgoUpsertControl(TradingCoreRuntime owner, long userId, CoreAlgoOrderView algo,
            int symbolId) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareAlgoUpsert(owner, userId, algo, symbolId);
        owner.runtimeState().dispatchControlLanes(
                1L << owner.runtimeState().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    public CoreResponse poll(TradingCoreRuntime owner) {
        owner.assertOwner();
        if (!active) throw new IllegalStateException("no pending direct command");
        if (fundsPreflight) return pollFundsCheck(owner);
        if (status == null) {
            try {
                if (!controlWork.getAsBoolean()) return null;
                status = ResponseStatus.APPLIED;
                resultCode = CoreResultCode.NONE;
            } catch (CoreStateRejectedException failure) {
                status = ResponseStatus.REJECTED;
                resultCode = CoreResultCode.fromRejectionCode(failure.code());
            } catch (ArithmeticException failure) {
                status = ResponseStatus.REJECTED;
                resultCode = CoreResultCode.ARITHMETIC_OVERFLOW;
            } catch (IllegalArgumentException failure) {
                status = ResponseStatus.REJECTED;
                resultCode = CoreResultCode.INVALID_COMMAND;
            }
            controlWork = null;
        }
        if (status != ResponseStatus.APPLIED
                && (owner.commits.publication.dirty()
                || owner.runtimeState().revision() != beforeRevision
                || owner.runtimeState().hasUncommittedCommandChanges())) {
            if (controlWork == null) {
                owner.commits.publication.abort();
                controlWork = owner.runtimeState().beginCommandRollback(
                        checkpoint, Math.incrementExact(owner.appliedCommandCount()));
            }
            if (!controlWork.getAsBoolean()) return null;
            owner.identities().rollbackPositionKeys(identityCheckpoint);
            controlWork = null;
        }
        return owner.finishDirectCommand(command, commitFenceTimestamp, commitFenceClusterPosition,
                sourceKey, fingerprint, beforeRevision, checkpoint,
                identityCheckpoint, status, resultCode);
    }

    public void clear() {
        fundingControl.clear();
        riskScanControl.clear();
        adlControl.clear();
        liquidationResolutionControl.clear();
        accountControl.clear();
        ownerRef = null;
        triggerControl.clear();
        fundsIndexWork.clear();
        active = false;
        command = null;
        fingerprint = null;
        sourceKey = null;
        beforeRevision = checkpoint = identityCheckpoint = 0;
        controlWork = null;
        status = null;
        resultCode = null;
        commitEvent = null;
        finalizationPrepared = false;
        publicationComplete = fundsPreflight = fundsRetainPrepared = false;
        fundsLastSourceSequence = 0;
        commitFenceTimestamp = commitFenceClusterPosition = 0;
    }

    private final class FundingControlContinuation implements BooleanSupplier {
        private TradingCoreRuntime owner;
        private RuntimePerpetualFundingProcessor.FundingWork work;

        void prepare(TradingCoreRuntime owner, RuntimePerpetualFundingProcessor.FundingWork work) {
            this.owner = Objects.requireNonNull(owner);
            this.work = Objects.requireNonNull(work);
        }
        void clear() { owner = null; work = null; }

        @Override public boolean getAsBoolean() {
            if (!work.poll()) return false;
            var result = work.result();
            if (result.state() != owner.runtimeState()) {
                throw new IllegalStateException("funding processor replaced authoritative runtime state");
            }
            owner.requestCommitPublication();
            owner.resultBuilder.commandFundingProgress = result.progress();
            owner.resultBuilder.addChangedUsersFromFundingPayments(result.payments());
            owner.funding().notifyReplayObserver(result);
            return true;
        }
    }

    private final class RiskScanControlContinuation implements BooleanSupplier {
        private TradingCoreRuntime owner;
        private RiskScanCoordinator risk;
        private int symbolId;
        private String instrumentId;
        private int maxUsers;
        private int pendingBefore;
        private long startedAt;
        private long beforeRevision;
        private BooleanSupplier triggers;

        void prepare(TradingCoreRuntime owner, RiskScanCoordinator risk, int symbolId, String instrumentId,
                int maxUsers, int pendingBefore, long startedAt, long beforeRevision) {
            this.owner = Objects.requireNonNull(owner);
            this.risk = risk;
            this.symbolId = symbolId;
            this.instrumentId = Objects.requireNonNull(instrumentId);
            this.maxUsers = maxUsers;
            this.pendingBefore = pendingBefore;
            this.startedAt = startedAt;
            this.beforeRevision = beforeRevision;
            this.triggers = null;
        }
        void clear() {
            owner = null; risk = null; instrumentId = null; triggers = null;
            symbolId = maxUsers = pendingBefore = 0; startedAt = beforeRevision = 0;
        }

        @Override public boolean getAsBoolean() {
            if (triggers == null) {
                if (risk != null && !risk.poll()) return false;
                if (owner.runtimeState().revision() != beforeRevision) owner.requestCommitPublication();
                int remaining = maxUsers - (risk == null ? 0 : risk.completedWork());
                var completedScan = owner.runtimeState().riskScan(symbolId);
                triggers = remaining > 0 && completedScan != null
                        && !completedScan.triggerComplete()
                        ? owner.pendingTriggerScan(instrumentId, remaining) : COMPLETE_CONTROL;
            }
            if (!triggers.getAsBoolean()) return false;
            owner.logRiskScan("continuation", instrumentId, maxUsers, pendingBefore, startedAt);
            return true;
        }
    }

    private final class AdlControlContinuation implements BooleanSupplier {
        private TradingCoreRuntime owner;
        private RuntimeAdlExecution.AdlWork work;
        void prepare(TradingCoreRuntime owner, RuntimeAdlExecution.AdlWork work) {
            this.owner = Objects.requireNonNull(owner); this.work = Objects.requireNonNull(work);
        }
        void clear() { owner = null; work = null; }
        @Override public boolean getAsBoolean() {
            if (!work.getAsBoolean()) return false;
            owner.requestCommitPublication();
            return true;
        }
    }

    private final class LiquidationResolutionControlContinuation implements BooleanSupplier {
        private TradingCoreRuntime owner;
        private RuntimeLiquidationResolution.ResolutionWork work;
        void prepare(TradingCoreRuntime owner, RuntimeLiquidationResolution.ResolutionWork work) {
            this.owner = Objects.requireNonNull(owner); this.work = Objects.requireNonNull(work);
        }
        void clear() { owner = null; work = null; }
        @Override public boolean getAsBoolean() {
            if (!work.getAsBoolean()) return false;
            owner.requestCommitPublication();
            return true;
        }
    }

    /** Fixed-slot Lane operation and completion for direct trigger/algo commands. */
    private final class TriggerControlContinuation
            implements BooleanSupplier, IntFunction<Object> {
        private static final byte MUTATION = 1;
        private static final byte TRIGGER_UPSERT = 2;
        private static final byte ALGO_UPSERT = 3;
        private byte kind;
        private TradingCoreRuntime owner;
        private long userId, triggerOrderId, arg1, arg2, arg3;
        private boolean flag;
        private String text;
        private TriggerOrderCommands.Mutation mutation;
        private CoreTriggerOrderStateView trigger, secondTrigger;
        private CoreAlgoOrderView algo;
        private int symbolId;
        private long positionKey;
        private boolean instrumentSettled;

        void prepareMutation(TradingCoreRuntime owner, long userId,
                TriggerOrderCommands.Mutation mutation, long triggerOrderId, long arg1, long arg2,
                long arg3, boolean flag, String text) {
            clear(); kind = MUTATION; this.owner = Objects.requireNonNull(owner); this.userId = userId;
            this.mutation = Objects.requireNonNull(mutation); this.triggerOrderId = triggerOrderId;
            this.arg1 = arg1; this.arg2 = arg2; this.arg3 = arg3; this.flag = flag; this.text = text;
        }
        void prepareTriggerUpsert(TradingCoreRuntime owner, long userId,
                CoreTriggerOrderStateView trigger, int symbolId, long positionKey,
                boolean instrumentSettled) {
            clear(); kind = TRIGGER_UPSERT; this.owner = Objects.requireNonNull(owner);
            this.userId = userId; this.trigger = Objects.requireNonNull(trigger); this.symbolId = symbolId;
            this.positionKey = positionKey; this.instrumentSettled = instrumentSettled;
        }
        void prepareAlgoUpsert(TradingCoreRuntime owner, long userId, CoreAlgoOrderView algo,
                int symbolId) {
            clear(); kind = ALGO_UPSERT; this.owner = Objects.requireNonNull(owner);
            this.userId = userId; this.algo = Objects.requireNonNull(algo); this.symbolId = symbolId;
        }
        void clear() {
            kind = 0; owner = null; mutation = null; trigger = null; secondTrigger = null; algo = null; text = null;
            userId = triggerOrderId = arg1 = arg2 = arg3 = positionKey = 0;
            flag = instrumentSettled = false; symbolId = 0;
        }

        @Override public Object apply(int ignoredLaneId) {
            var runtime = owner.runtimeState();
            return switch (kind) {
                case MUTATION -> switch (mutation) {
                    case CANCEL -> RuntimeTriggerOrderStateTransitions.cancel(runtime, userId, triggerOrderId);
                    case CLAIM -> RuntimeTriggerOrderStateTransitions.claim(runtime, triggerOrderId, arg1, arg2, arg3);
                    case COMPLETE -> RuntimeTriggerOrderStateTransitions.complete(runtime, triggerOrderId,
                            flag, arg1, text == null ? "" : text, arg2);
                    case TRAILING -> RuntimeTriggerOrderStateTransitions.updateTrailing(runtime, triggerOrderId,
                            arg1, arg2, arg3);
                    case EXPIRE -> RuntimeTriggerOrderStateTransitions.expire(runtime, triggerOrderId, arg1);
                    case RETRY -> RuntimeTriggerOrderStateTransitions.retry(runtime, triggerOrderId, arg1, arg2);
                };
                case TRIGGER_UPSERT -> {
                    if (secondTrigger == null) RuntimeTriggerOrderStateTransitions.upsert(runtime, userId, trigger, symbolId,
                            positionKey, instrumentSettled);
                    else RuntimeTriggerOrderStateTransitions.placeOcoPair(runtime, userId, trigger, secondTrigger,
                            symbolId, positionKey, instrumentSettled);
                    yield secondTrigger == null ? runtime.triggerOrder(trigger.triggerOrderId()).view()
                            : java.util.List.of(runtime.triggerOrder(trigger.triggerOrderId()).view(),
                                    runtime.triggerOrder(secondTrigger.triggerOrderId()).view());
                }
                case ALGO_UPSERT -> {
                    RuntimeAlgoOrderStateTransitions.upsert(runtime, userId, algo, symbolId);
                    yield runtime.algoOrder(algo.algoOrderId());
                }
                default -> throw new IllegalStateException("unknown trigger continuation");
            };
        }

        @Override public boolean getAsBoolean() {
            if (!owner.runtimeState().pollControlLanes()) return false;
            Object result = owner.runtimeState().controlLaneResult(
                    owner.runtimeState().topology().accountLaneId(userId));
            if (kind == MUTATION) {
                if (Boolean.TRUE.equals(result)) owner.requestCommitPublication();
            } else if (kind == TRIGGER_UPSERT) {
                owner.requestCommitPublication();
                if (secondTrigger == null) owner.resultBuilder.commandTriggerOrderViews = java.util.List.of((CoreTriggerOrderStateView) result);
                else {
                    @SuppressWarnings("unchecked") var views = (java.util.List<CoreTriggerOrderStateView>) result;
                    owner.resultBuilder.commandTriggerOrderViews = views;
                }
            } else {
                owner.requestCommitPublication();
            }
            return true;
        }
    }
}
