package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.account.TransferRuntime;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreLeverageKey;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/**
 * 统一的账户 Lane 控制任务。
 *
 * <p>将零散的余额调整、资金划转出入、杠杆变更、持仓模式和保证金调整统一收敛至本单一执行单元，
 * 消除同质化微型类的类爆炸，由所属账户 Lane 异步单写执行，Owner 收集并提交产品元数据。</p>
 */
public final class AccountControlTask implements IntFunction<Object>, BooleanSupplier {
    public static final byte KIND_BALANCE = 1;
    public static final byte KIND_TRANSFER_OUT = 2;
    public static final byte KIND_TRANSFER_COMPLETE = 3;
    public static final byte KIND_LEVERAGE = 4;
    public static final byte KIND_POSITION_MODE = 5;
    public static final byte KIND_POSITION_MARGIN = 6;

    private byte kind;
    private TradingRuntimeState runtime;
    private long userId;
    private int laneId;
    private long beforeRevision;
    private long nextRevision;
    private boolean completed;
    private boolean stateChanged;

    // Balance
    private int assetId;
    private long deltaUnits;

    // Transfer
    private TransferFundsCommand transferCommand;
    private boolean transferCreated;
    private long transferId;
    private boolean transferRemoved;

    // Leverage
    private CoreLeverageKey leverageKey;
    private long leveragePpm;
    private boolean repriceCrossMargin;
    private int symbolId;

    // Position Mode
    private UpdatePositionModeCommand positionModeCommand;

    // Position Margin
    private long positionKey;
    private AdjustPositionMarginCommand positionMarginCommand;

    public AccountControlTask() {
    }

    public AccountControlTask prepareBalanceAdjustment(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                                      long userId, BalanceAdjustmentCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0)
            throw new IllegalArgumentException("invalid account balance adjustment");
        runtime.assertOwner();
        clear();
        this.kind = KIND_BALANCE;
        this.runtime = runtime;
        this.userId = userId;
        this.assetId = identities.assetId(AssetBalance.normalizeAsset(command.asset()));
        this.deltaUnits = command.deltaUnits();
        this.nextRevision = Math.incrementExact(runtime.revision());
        this.completed = false;
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
        return this;
    }

    public AccountControlTask prepareTransferOut(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                                long userId, TransferFundsCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0)
            throw new IllegalArgumentException("transfer inputs are required");
        runtime.assertOwner();
        RuntimeAccountStateTransitions.validateTransferSource(runtime, userId, command);
        long routedUser = runtime.pendingTransferUser(command.transferId());
        if (routedUser != 0 && routedUser != userId)
            throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        if (routedUser == 0 && !runtime.hasPendingTransferCapacity())
            throw new CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL", "pending transfer runtime capacity is full");
        clear();
        this.kind = KIND_TRANSFER_OUT;
        this.runtime = runtime;
        this.userId = userId;
        this.transferCommand = command;
        this.assetId = identities.assetId(command.asset());
        this.transferCreated = false;
        this.completed = false;
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
        return this;
    }

    public AccountControlTask prepareTransferCompletion(TradingRuntimeState runtime, long userId, long transferId) {
        if (runtime == null || userId <= 0 || transferId <= 0)
            throw new IllegalArgumentException("invalid transfer completion");
        runtime.assertOwner();
        long routed = runtime.pendingTransferUser(transferId);
        if (routed != 0 && routed != userId)
            throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        clear();
        this.kind = KIND_TRANSFER_COMPLETE;
        this.runtime = runtime;
        this.userId = userId;
        this.transferId = transferId;
        this.transferRemoved = false;
        this.completed = routed == 0;
        if (!this.completed) runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
        return this;
    }

    public AccountControlTask prepareLeverageChange(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                                   long userId, UpdateLeverageCommand command) {
        return prepareLeverageChange(runtime, identities, userId, command, 0);
    }

    public AccountControlTask prepareLeverageChange(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                                   long userId, UpdateLeverageCommand command, long beforeRevision) {
        clear();
        this.kind = KIND_LEVERAGE;
        this.runtime = Objects.requireNonNull(runtime);
        this.beforeRevision = beforeRevision;
        this.leverageKey = DerivativeAccountCommandProcessor.leverageKey(runtime, identities, userId, command);
        this.leveragePpm = command.leveragePpm();
        this.repriceCrossMargin = command.repriceCrossMargin();
        this.laneId = runtime.topology().accountLaneId(userId);
        this.symbolId = identities.symbolId(leverageKey.instrumentId());
        this.completed = false;
        runtime.dispatchControlLanes(1L << laneId, this);
        return this;
    }

    public AccountControlTask preparePositionModeChange(TradingRuntimeState runtime, long userId,
                                                       UpdatePositionModeCommand command) {
        return preparePositionModeChange(runtime, userId, command, 0);
    }

    public AccountControlTask preparePositionModeChange(TradingRuntimeState runtime, long userId,
                                                       UpdatePositionModeCommand command, long beforeRevision) {
        if (runtime == null || userId <= 0 || command == null)
            throw new IllegalArgumentException("invalid account position mode change");
        runtime.assertOwner();
        clear();
        this.kind = KIND_POSITION_MODE;
        this.runtime = runtime;
        this.beforeRevision = beforeRevision;
        this.userId = userId;
        this.positionModeCommand = command;
        this.laneId = runtime.topology().accountLaneId(userId);
        this.completed = false;
        runtime.dispatchControlLanes(1L << laneId, this);
        return this;
    }

    public AccountControlTask preparePositionMarginAdjustment(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                                             long userId, AdjustPositionMarginCommand command) {
        clear();
        this.kind = KIND_POSITION_MARGIN;
        this.runtime = Objects.requireNonNull(runtime);
        this.positionKey = DerivativeAccountCommandProcessor.positionMarginKey(runtime, identities, userId, command);
        this.userId = userId;
        this.positionMarginCommand = command;
        this.laneId = runtime.topology().accountLaneId(userId);
        this.nextRevision = Math.incrementExact(runtime.revision());
        this.completed = false;
        runtime.dispatchControlLanes(1L << laneId, this);
        return this;
    }

    public void clear() {
        kind = 0;
        runtime = null;
        userId = 0;
        laneId = 0;
        beforeRevision = 0;
        nextRevision = 0;
        completed = false;
        stateChanged = false;
        assetId = 0;
        deltaUnits = 0;
        transferCommand = null;
        transferCreated = false;
        transferId = 0;
        transferRemoved = false;
        leverageKey = null;
        leveragePpm = 0;
        repriceCrossMargin = false;
        symbolId = 0;
        positionModeCommand = null;
        positionKey = 0;
        positionMarginCommand = null;
    }

    @Override public Object apply(int ignoredLaneId) {
        return switch (kind) {
            case KIND_BALANCE -> {
                RuntimeAccountStateTransitions.adjustAccountBalance(runtime, userId, assetId, deltaUnits);
                yield null;
            }
            case KIND_TRANSFER_OUT -> {
                TransferRuntime transfer = RuntimeAccountStateTransitions.prepareTransferOut(runtime, userId, transferCommand);
                if (transfer != null) {
                    runtime.requireRevisionCapacity();
                    RuntimeAccountStateTransitions.debitTransferAccount(runtime, userId, assetId, transferCommand.amountUnits());
                    runtime.putPendingTransfer(transfer);
                    runtime.incrementCommandRevision();
                    transferCreated = true;
                }
                yield null;
            }
            case KIND_TRANSFER_COMPLETE -> {
                transferRemoved = RuntimeAccountStateTransitions.completeTransfer(runtime, userId, transferId);
                yield null;
            }
            case KIND_LEVERAGE -> DerivativeAccountCommandProcessor.updateAccountLeverage(
                    runtime, leverageKey, symbolId, leveragePpm, repriceCrossMargin);
            case KIND_POSITION_MODE -> DerivativeAccountCommandProcessor.updateAccountPositionMode(
                    runtime, userId, positionModeCommand);
            case KIND_POSITION_MARGIN -> DerivativeAccountCommandProcessor.adjustAccountPositionMargin(
                    runtime, userId, positionKey, positionMarginCommand);
            default -> throw new IllegalStateException("unknown account control task kind: " + kind);
        };
    }

    public boolean poll() {
        runtime.assertOwner();
        if (completed) return true;
        if (!runtime.pollControlLanes()) return false;
        switch (kind) {
            case KIND_BALANCE -> {
                runtime.markBalanceChanged(userId, assetId);
                runtime.setMetadata(runtime.productLine(), nextRevision);
                stateChanged = true;
            }
            case KIND_TRANSFER_OUT -> {
                if (transferCreated) runtime.registerPendingTransferRoute(transferCommand.transferId(), userId);
                transferCommand = null;
                stateChanged = true;
            }
            case KIND_TRANSFER_COMPLETE -> {
                if (!transferRemoved) throw new IllegalStateException("pending transfer route has no Lane identity");
                runtime.completePendingTransferRoute(transferId, userId);
                stateChanged = true;
            }
            case KIND_LEVERAGE -> {
                if (Boolean.TRUE.equals(runtime.controlLaneResult(laneId))) {
                    runtime.recordLeverageChange(leverageKey, leveragePpm);
                    runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
                    if (runtime.revision() != beforeRevision) stateChanged = true;
                }
            }
            case KIND_POSITION_MODE -> {
                if (Boolean.TRUE.equals(runtime.controlLaneResult(laneId))) {
                    runtime.markUserChanged(userId);
                    runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
                    if (runtime.revision() != beforeRevision) stateChanged = true;
                }
            }
            case KIND_POSITION_MARGIN -> {
                int marginAssetId = (Integer) runtime.controlLaneResult(laneId);
                runtime.recordUserSettlementChanges(userId, marginAssetId, positionKey);
                runtime.setMetadata(runtime.productLine(), nextRevision);
                stateChanged = true;
            }
            default -> throw new IllegalStateException("unknown account control task kind: " + kind);
        }
        completed = true;
        return true;
    }

    public boolean stateChanged() {
        return stateChanged;
    }

    @Override public boolean getAsBoolean() {
        return poll();
    }
}
