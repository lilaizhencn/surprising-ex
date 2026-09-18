package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.service.command.CommandResultContext;
import com.surprising.aeron.service.command.balance.BalanceCommandContext;
import com.surprising.aeron.service.command.risk.RiskCommandContext;
import com.surprising.aeron.service.command.trigger.TriggerCommandContext;
import com.surprising.aeron.service.state.AccountBalanceAdjustment;
import com.surprising.aeron.service.state.AccountLeverageChange;
import com.surprising.aeron.service.state.AccountPositionMarginAdjustment;
import com.surprising.aeron.service.state.AccountPositionModeChange;
import com.surprising.aeron.service.state.AccountTransferOut;
import com.surprising.aeron.service.state.RiskScanCoordinator;
import com.surprising.aeron.service.state.RuntimeCommandProcessor;
import com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor;
import com.surprising.aeron.service.state.RuntimePerpetualFundingProcessor;
import com.surprising.aeron.service.state.RuntimeProjectionPoint;

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
final class DirectCommandSlot {
    private CoreMessage command;
    private CommandFingerprint fingerprint;
    private TradingCoreRuntime.SourceKey sourceKey;
    private RuntimeProjectionPoint beforeProjection;
    private long commitFenceTimestamp;
    private long commitFenceClusterPosition;

    private boolean active;
    private long beforeRevision;
    private long checkpoint;
    private long identityCheckpoint;
    private BooleanSupplier controlWork;
    private ResponseStatus status;
    private CoreResultCode resultCode;
    private com.surprising.aeron.service.state.LaneCommitEvent commitEvent;
    private boolean finalizationPrepared;

    private final FundingControlContinuation fundingControl = new FundingControlContinuation();
    private final RiskScanControlContinuation riskScanControl = new RiskScanControlContinuation();
    private final AdlControlContinuation adlControl = new AdlControlContinuation();
    private final LiquidationResolutionControlContinuation liquidationResolutionControl =
            new LiquidationResolutionControlContinuation();
    private final AccountControlContinuation accountControl = new AccountControlContinuation();
    private final TriggerControlContinuation triggerControl = new TriggerControlContinuation();
    private static final BooleanSupplier COMPLETE_CONTROL = () -> true;

    private RuntimePerpetualFundingProcessor.FundingWork reusableFundingWork;
    private RuntimeDerivativeLiquidationProcessor.AdlWork reusableAdlWork;
    private RuntimeDerivativeLiquidationProcessor.ResolutionWork reusableResolutionWork;
    private RiskScanCoordinator reusableRiskCoordinator;
    private AccountBalanceAdjustment reusableBalanceAdjustment;
    private AccountTransferOut reusableTransferOut;
    private AccountLeverageChange reusableLeverageChange;
    private AccountPositionModeChange reusablePositionModeChange;
    private AccountPositionMarginAdjustment reusablePositionMarginAdjustment;

    boolean active() { return active; }

    void initialize(CoreMessage command, CommandFingerprint fingerprint,
            TradingCoreRuntime.SourceKey sourceKey, long timestamp, long position,
            RuntimeProjectionPoint beforeProjection, long beforeRevision,
            long checkpoint, long identityCheckpoint) {
        if (active) throw new IllegalStateException("direct command slot is occupied");
        this.command = Objects.requireNonNull(command);
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.sourceKey = Objects.requireNonNull(sourceKey);
        this.beforeProjection = beforeProjection;
        this.beforeRevision = beforeRevision;
        this.checkpoint = checkpoint;
        this.identityCheckpoint = identityCheckpoint;
        commitFenceTimestamp = timestamp;
        commitFenceClusterPosition = position;
        active = true;
    }

    boolean hasControlWork() { return controlWork != null; }
    BooleanSupplier controlWork() { return controlWork; }
    void replaceControlWork(BooleanSupplier work) { controlWork = Objects.requireNonNull(work); }
    CoreMessage command() { return command; }
    ResponseStatus status() { return status; }
    CoreResultCode resultCode() { return resultCode; }
    void result(ResponseStatus status, CoreResultCode resultCode) {
        this.status = status;
        this.resultCode = resultCode;
    }
    boolean finalizationPrepared() { return finalizationPrepared; }
    void markFinalizationPrepared() { finalizationPrepared = true; }
    void clearFinalizationPrepared() { finalizationPrepared = false; }
    com.surprising.aeron.service.state.LaneCommitEvent commitEvent() { return commitEvent; }
    void commitEvent(com.surprising.aeron.service.state.LaneCommitEvent event) { commitEvent = event; }
    void clearCommitEvent() { commitEvent = null; }
    long commitFenceTimestamp() { return commitFenceTimestamp; }
    long checkpoint() { return checkpoint; }

    void deferControl(BooleanSupplier work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        controlWork = Objects.requireNonNull(work);
    }

    void deferFundingControl(CommandResultContext owner,
            RuntimePerpetualFundingProcessor.FundingWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        fundingControl.prepare(owner, work);
        reusableFundingWork = work;
        controlWork = fundingControl;
    }

    RuntimePerpetualFundingProcessor.FundingWork reusableFundingWork() { return reusableFundingWork; }

    void deferRiskScanControl(RiskCommandContext owner, RiskScanCoordinator risk, int symbolId,
            String symbol, int maxUsers, int pendingBefore, long startedAt, long beforeRevision) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        riskScanControl.prepare(owner, risk, symbolId, symbol, maxUsers, pendingBefore, startedAt,
                beforeRevision);
        controlWork = riskScanControl;
    }

    void deferAdlControl(CommandResultContext owner,
            RuntimeDerivativeLiquidationProcessor.AdlWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        adlControl.prepare(owner, work);
        reusableAdlWork = work;
        controlWork = adlControl;
    }

    RuntimeDerivativeLiquidationProcessor.AdlWork reusableAdlWork() { return reusableAdlWork; }

    void deferLiquidationResolutionControl(CommandResultContext owner,
            RuntimeDerivativeLiquidationProcessor.ResolutionWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        liquidationResolutionControl.prepare(owner, work);
        reusableResolutionWork = work;
        controlWork = liquidationResolutionControl;
    }

    RuntimeDerivativeLiquidationProcessor.ResolutionWork reusableLiquidationResolutionWork() {
        return reusableResolutionWork;
    }

    RiskScanCoordinator reusableRiskScanCoordinator(int maxUsers,
            com.surprising.aeron.service.state.PositionUserIndex positionUserIndex,
            com.surprising.aeron.service.state.TradingRuntimeState runtime,
            com.surprising.aeron.service.state.RuntimeIdentityRegistry identities) {
        if (reusableRiskCoordinator == null) {
            reusableRiskCoordinator = new RiskScanCoordinator(maxUsers, positionUserIndex, runtime, identities);
        } else {
            reusableRiskCoordinator.resetForCommand(maxUsers);
        }
        return reusableRiskCoordinator;
    }

    AccountBalanceAdjustment reusableBalanceAdjustment() { return reusableBalanceAdjustment; }
    AccountTransferOut reusableTransferOut() { return reusableTransferOut; }
    AccountLeverageChange reusableLeverageChange() { return reusableLeverageChange; }
    AccountPositionModeChange reusablePositionModeChange() { return reusablePositionModeChange; }
    AccountPositionMarginAdjustment reusablePositionMarginAdjustment() { return reusablePositionMarginAdjustment; }

    void deferBalanceAdjustmentControl(CommandResultContext owner, AccountBalanceAdjustment work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        reusableBalanceAdjustment = Objects.requireNonNull(work);
        accountControl.prepareBalance(owner, work);
        controlWork = accountControl;
    }

    void deferTransferOutControl(CommandResultContext owner, AccountTransferOut work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        reusableTransferOut = Objects.requireNonNull(work);
        accountControl.prepareTransfer(owner, work);
        controlWork = accountControl;
    }

    void deferLeverageChangeControl(CommandResultContext owner, AccountLeverageChange work,
            long beforeRevision) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        reusableLeverageChange = Objects.requireNonNull(work);
        accountControl.prepareLeverage(owner, work, beforeRevision);
        controlWork = accountControl;
    }

    void deferPositionModeChangeControl(CommandResultContext owner, AccountPositionModeChange work,
            long beforeRevision) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        reusablePositionModeChange = Objects.requireNonNull(work);
        accountControl.preparePositionMode(owner, work, beforeRevision);
        controlWork = accountControl;
    }

    void deferPositionMarginAdjustmentControl(CommandResultContext owner,
            AccountPositionMarginAdjustment work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        reusablePositionMarginAdjustment = Objects.requireNonNull(work);
        accountControl.preparePositionMargin(owner, work);
        controlWork = accountControl;
    }

    void deferTriggerMutationControl(TriggerCommandContext owner, long userId,
            TriggerCommandContext.Mutation mutation, long triggerOrderId, long arg1, long arg2,
            long arg3, boolean flag, String text) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareMutation(owner, userId, mutation, triggerOrderId, arg1, arg2, arg3,
                flag, text);
        owner.runtimeState().dispatchControlLanes(
                1L << owner.runtimeState().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    void deferTriggerUpsertControl(TriggerCommandContext owner, long userId,
            CoreTriggerOrderStateView trigger, int symbolId, long positionKey,
            boolean instrumentSettled) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareTriggerUpsert(owner, userId, trigger, symbolId, positionKey,
                instrumentSettled);
        owner.runtimeState().dispatchControlLanes(
                1L << owner.runtimeState().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    void deferAlgoUpsertControl(TriggerCommandContext owner, long userId, CoreAlgoOrderView algo,
            int symbolId) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareAlgoUpsert(owner, userId, algo, symbolId);
        owner.runtimeState().dispatchControlLanes(
                1L << owner.runtimeState().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    CoreResponse poll(TradingCoreRuntime owner) {
        owner.assertOwner();
        if (!active) throw new IllegalStateException("no pending direct command");
        if (status == null) {
            try {
                if (!controlWork.getAsBoolean()) return null;
                status = ResponseStatus.APPLIED;
                resultCode = CoreResultCode.NONE;
            } catch (com.surprising.aeron.service.state.CoreStateRejectedException failure) {
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
                && (owner.commits.commitPublicationDirty()
                || owner.runtimeState.revision() != beforeRevision
                || owner.runtimeState.hasUncommittedCommandChanges())) {
            if (controlWork == null) {
                owner.commits.abortCommitPublicationBatch();
                controlWork = owner.runtimeState.beginCommandRollback(
                        checkpoint, Math.incrementExact(owner.appliedCommandCount));
            }
            if (!controlWork.getAsBoolean()) return null;
            owner.identities.rollbackPositionKeys(identityCheckpoint);
            controlWork = null;
        }
        return owner.finishDirectCommand(command, commitFenceTimestamp, commitFenceClusterPosition,
                sourceKey, fingerprint, beforeProjection, beforeRevision, checkpoint,
                identityCheckpoint, status, resultCode);
    }

    void clear() {
        fundingControl.clear();
        riskScanControl.clear();
        adlControl.clear();
        liquidationResolutionControl.clear();
        accountControl.clear();
        triggerControl.clear();
        active = false;
        command = null;
        fingerprint = null;
        sourceKey = null;
        beforeProjection = null;
        beforeRevision = checkpoint = identityCheckpoint = 0;
        controlWork = null;
        status = null;
        resultCode = null;
        commitEvent = null;
        finalizationPrepared = false;
        commitFenceTimestamp = commitFenceClusterPosition = 0;
    }

    private final class FundingControlContinuation implements BooleanSupplier {
        private CommandResultContext owner;
        private RuntimePerpetualFundingProcessor.FundingWork work;

        void prepare(CommandResultContext owner, RuntimePerpetualFundingProcessor.FundingWork work) {
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
            owner.setCommandFundingProgress(result.progress());
            owner.addChangedUsersFromFundingPayments(result.payments());
            return true;
        }
    }

    private final class RiskScanControlContinuation implements BooleanSupplier {
        private RiskCommandContext owner;
        private RiskScanCoordinator risk;
        private int symbolId;
        private String symbol;
        private int maxUsers;
        private int pendingBefore;
        private long startedAt;
        private long beforeRevision;
        private BooleanSupplier triggers;

        void prepare(RiskCommandContext owner, RiskScanCoordinator risk, int symbolId, String symbol,
                int maxUsers, int pendingBefore, long startedAt, long beforeRevision) {
            this.owner = Objects.requireNonNull(owner);
            this.risk = risk;
            this.symbolId = symbolId;
            this.symbol = Objects.requireNonNull(symbol);
            this.maxUsers = maxUsers;
            this.pendingBefore = pendingBefore;
            this.startedAt = startedAt;
            this.beforeRevision = beforeRevision;
            this.triggers = null;
        }
        void clear() {
            owner = null; risk = null; symbol = null; triggers = null;
            symbolId = maxUsers = pendingBefore = 0; startedAt = beforeRevision = 0;
        }

        @Override public boolean getAsBoolean() {
            if (triggers == null) {
                if (risk != null && !risk.poll()) return false;
                if (owner.runtimeState().revision() != beforeRevision) owner.requestCommitPublication();
                int remaining = maxUsers - (risk == null ? 0 : risk.completedWork());
                var completedScan = owner.runtimeState().riskScan(symbolId);
                triggers = remaining > 0 && completedScan != null && completedScan.riskComplete()
                        && !completedScan.triggerComplete()
                        ? owner.pendingTriggerScan(symbol, remaining) : COMPLETE_CONTROL;
            }
            if (!triggers.getAsBoolean()) return false;
            owner.logRiskScan("continuation", symbol, maxUsers, pendingBefore, startedAt);
            return true;
        }
    }

    private final class AdlControlContinuation implements BooleanSupplier {
        private CommandResultContext owner;
        private RuntimeDerivativeLiquidationProcessor.AdlWork work;
        void prepare(CommandResultContext owner, RuntimeDerivativeLiquidationProcessor.AdlWork work) {
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
        private CommandResultContext owner;
        private RuntimeDerivativeLiquidationProcessor.ResolutionWork work;
        void prepare(CommandResultContext owner, RuntimeDerivativeLiquidationProcessor.ResolutionWork work) {
            this.owner = Objects.requireNonNull(owner); this.work = Objects.requireNonNull(work);
        }
        void clear() { owner = null; work = null; }
        @Override public boolean getAsBoolean() {
            if (!work.getAsBoolean()) return false;
            owner.requestCommitPublication();
            return true;
        }
    }

    /** Single fixed-slot continuation for ordinary account control commands. */
    private final class AccountControlContinuation implements BooleanSupplier {
        private static final byte BALANCE = 1;
        private static final byte TRANSFER = 2;
        private static final byte LEVERAGE = 3;
        private static final byte POSITION_MODE = 4;
        private static final byte POSITION_MARGIN = 5;
        private byte kind;
        private CommandResultContext owner;
        private AccountBalanceAdjustment balance;
        private AccountTransferOut transfer;
        private AccountLeverageChange leverage;
        private AccountPositionModeChange positionMode;
        private AccountPositionMarginAdjustment positionMargin;
        private long beforeRevision;

        void prepareBalance(CommandResultContext owner, AccountBalanceAdjustment work) {
            clear(); kind = BALANCE; balance = work; this.owner = Objects.requireNonNull(owner);
        }
        void prepareTransfer(CommandResultContext owner, AccountTransferOut work) {
            clear(); kind = TRANSFER; transfer = work; this.owner = Objects.requireNonNull(owner);
        }
        void prepareLeverage(CommandResultContext owner, AccountLeverageChange work,
                long beforeRevision) {
            clear(); kind = LEVERAGE; leverage = work; this.owner = Objects.requireNonNull(owner);
            this.beforeRevision = beforeRevision;
        }
        void preparePositionMode(CommandResultContext owner, AccountPositionModeChange work,
                long beforeRevision) {
            clear(); kind = POSITION_MODE; positionMode = work; this.owner = Objects.requireNonNull(owner);
            this.beforeRevision = beforeRevision;
        }
        void preparePositionMargin(CommandResultContext owner,
                AccountPositionMarginAdjustment work) {
            clear(); kind = POSITION_MARGIN; positionMargin = work; this.owner = Objects.requireNonNull(owner);
        }
        void clear() {
            kind = 0; owner = null; balance = null; transfer = null; leverage = null;
            positionMode = null; positionMargin = null; beforeRevision = 0;
        }

        @Override public boolean getAsBoolean() {
            if (owner == null) throw new IllegalStateException("account continuation owner is missing");
            boolean complete;
            switch (kind) {
                case BALANCE -> {
                    complete = balance.poll();
                    if (complete) owner.requestCommitPublication();
                }
                case TRANSFER -> {
                    complete = transfer.poll();
                    if (complete) {
                        ((BalanceCommandContext) owner).refreshTransferHash();
                        owner.requestCommitPublication();
                    }
                }
                case LEVERAGE -> {
                    complete = leverage.poll();
                    if (complete && owner.runtimeState().revision() != beforeRevision)
                        owner.requestCommitPublication();
                }
                case POSITION_MODE -> {
                    complete = positionMode.poll();
                    if (complete && owner.runtimeState().revision() != beforeRevision)
                        owner.requestCommitPublication();
                }
                case POSITION_MARGIN -> {
                    complete = positionMargin.poll();
                    if (complete) owner.requestCommitPublication();
                }
                default -> throw new IllegalStateException("unknown account continuation");
            }
            return complete;
        }
    }

    /** Fixed-slot Lane operation and completion for direct trigger/algo commands. */
    private final class TriggerControlContinuation
            implements BooleanSupplier, IntFunction<Object> {
        private static final byte MUTATION = 1;
        private static final byte TRIGGER_UPSERT = 2;
        private static final byte ALGO_UPSERT = 3;
        private byte kind;
        private TriggerCommandContext owner;
        private long userId, triggerOrderId, arg1, arg2, arg3;
        private boolean flag;
        private String text;
        private TriggerCommandContext.Mutation mutation;
        private CoreTriggerOrderStateView trigger;
        private CoreAlgoOrderView algo;
        private int symbolId;
        private long positionKey;
        private boolean instrumentSettled;

        void prepareMutation(TriggerCommandContext owner, long userId,
                TriggerCommandContext.Mutation mutation, long triggerOrderId, long arg1, long arg2,
                long arg3, boolean flag, String text) {
            clear(); kind = MUTATION; this.owner = Objects.requireNonNull(owner); this.userId = userId;
            this.mutation = Objects.requireNonNull(mutation); this.triggerOrderId = triggerOrderId;
            this.arg1 = arg1; this.arg2 = arg2; this.arg3 = arg3; this.flag = flag; this.text = text;
        }
        void prepareTriggerUpsert(TriggerCommandContext owner, long userId,
                CoreTriggerOrderStateView trigger, int symbolId, long positionKey,
                boolean instrumentSettled) {
            clear(); kind = TRIGGER_UPSERT; this.owner = Objects.requireNonNull(owner);
            this.userId = userId; this.trigger = Objects.requireNonNull(trigger); this.symbolId = symbolId;
            this.positionKey = positionKey; this.instrumentSettled = instrumentSettled;
        }
        void prepareAlgoUpsert(TriggerCommandContext owner, long userId, CoreAlgoOrderView algo,
                int symbolId) {
            clear(); kind = ALGO_UPSERT; this.owner = Objects.requireNonNull(owner);
            this.userId = userId; this.algo = Objects.requireNonNull(algo); this.symbolId = symbolId;
        }
        void clear() {
            kind = 0; owner = null; mutation = null; trigger = null; algo = null; text = null;
            userId = triggerOrderId = arg1 = arg2 = arg3 = positionKey = 0;
            flag = instrumentSettled = false; symbolId = 0;
        }

        @Override public Object apply(int ignoredLaneId) {
            var runtime = owner.runtimeState();
            return switch (kind) {
                case MUTATION -> switch (mutation) {
                    case CANCEL -> RuntimeCommandProcessor.cancelTriggerOrder(runtime, userId, triggerOrderId);
                    case CLAIM -> RuntimeCommandProcessor.claimTriggerOrder(runtime, triggerOrderId, arg1, arg2, arg3);
                    case COMPLETE -> RuntimeCommandProcessor.completeTriggerOrder(runtime, triggerOrderId,
                            flag, arg1, text == null ? "" : text, arg2);
                    case TRAILING -> RuntimeCommandProcessor.updateTriggerTrailing(runtime, triggerOrderId,
                            arg1, arg2, arg3);
                    case EXPIRE -> RuntimeCommandProcessor.expireTriggerOrder(runtime, triggerOrderId, arg1);
                    case RETRY -> RuntimeCommandProcessor.retryTriggerOrder(runtime, triggerOrderId, arg1, arg2);
                };
                case TRIGGER_UPSERT -> {
                    RuntimeCommandProcessor.upsertTriggerOrder(runtime, userId, trigger, symbolId,
                            positionKey, instrumentSettled);
                    yield null;
                }
                case ALGO_UPSERT -> {
                    RuntimeCommandProcessor.upsertAlgoOrder(runtime, userId, algo, symbolId);
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
                owner.setCommandTriggerOrderView(owner.runtimeState().triggerOrder(trigger.triggerOrderId()).view());
            } else {
                owner.runtimeState().publishAlgoOrder(
                        (com.surprising.aeron.service.state.model.CoreAlgoOrderState) result);
                owner.requestCommitPublication();
            }
            return true;
        }
    }
}
