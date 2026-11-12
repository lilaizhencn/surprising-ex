package com.surprising.aeron.service.account;

import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.order.*;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.InstrumentId;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.command.DirectCommandDispatcher;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.OrderFundsReservation.Snapshot;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.support.math.CoreContractMath;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** 账户设置、资金划转与保证金变更命令服务（合并原 AccountControlTask、DerivativeAccountCommandProcessor 与 ControlLaneDispatcher）。 */
@NoArgsConstructor
public final class AccountCommandService implements IntFunction<Object>, BooleanSupplier {
    private static final byte KIND_BALANCE = 1;
    private static final byte KIND_TRANSFER_OUT = 2;
    private static final byte KIND_TRANSFER_COMPLETE = 3;
    private static final byte KIND_LEVERAGE = 4;
    private static final byte KIND_POSITION_MODE = 5;
    private static final byte KIND_POSITION_MARGIN = 6;

    private byte kind;
    /** 本续步关联的交易运行时，Owner 与账户分片通过既有控制派发交接。 */
    private AccountBook accountBook;
    private long userId;
    private long nextRevision;
    /** 本续步是否已由 Owner 收集并完成，或划转完成路径无需派发。 */
    private boolean completed;
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean stateChanged;

    private int assetId;
    private long deltaUnits;

    /** 当前转出命令，分片处理完且 Owner 收集后解除引用。 */
    private TransferFundsCommand transferCommand;
    /** Owner 收齐回执后才读取。 */
    private boolean changedOnLane;
    private long transferId;
    /** 分片是否确实移除了对应待完成记录，Owner 据此核对路由一致性。 */
    private boolean transferRemoved;

    private CoreLeverageKey leverageKey;
    /** 原始不可变杠杆命令，包含目标杠杆及全仓重定价开关。 */
    private UpdateLeverageCommand leverageCommand;
    private int symbolId;

    private UpdatePositionModeCommand positionModeCommand;

    private long positionKey;
    private AdjustPositionMarginCommand positionMarginCommand;

    public AccountCommandService prepareBalanceAdjustment(AccountBook accountBook, IdIndex identities,
                                                          long userId, String asset, long deltaUnits) {
        if (accountBook == null || identities == null || asset == null || asset.isBlank() || userId <= 0)
            throw new IllegalArgumentException("invalid account balance adjustment");
        accountBook.assertOwner();
        clear();
        this.kind = KIND_BALANCE;
        this.accountBook = accountBook;
        this.userId = userId;
        this.assetId = identities.assetId(AssetBalance.normalizeAsset(asset));
        this.deltaUnits = deltaUnits;
        this.nextRevision = Math.incrementExact(accountBook.revision());
        accountBook.dispatchControlLanes(accountBook.topology().accountLaneMask(userId), this);
        return this;
    }

    public AccountCommandService prepareTransferOut(AccountBook accountBook, IdIndex identities,
                                                    long userId, TransferFundsCommand command) {
        if (accountBook == null || identities == null || command == null || userId <= 0)
            throw new IllegalArgumentException("transfer inputs are required");
        accountBook.assertOwner();
        validateTransferSource(accountBook, userId, command);
        long routedUser = accountBook.pendingTransferUser(command.transferId());
        if (routedUser != 0 && routedUser != userId)
            throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        if (routedUser == 0 && !accountBook.hasPendingTransferCapacity())
            throw new CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL", "pending transfer runtime capacity is full");
        clear();
        this.kind = KIND_TRANSFER_OUT;
        this.accountBook = accountBook;
        this.userId = userId;
        this.transferCommand = command;
        this.assetId = identities.assetId(command.asset());
        accountBook.dispatchControlLanes(accountBook.topology().accountLaneMask(userId), this);
        return this;
    }

    public AccountCommandService prepareTransferCompletion(AccountBook accountBook, long userId, long transferId) {
        if (accountBook == null || userId <= 0 || transferId <= 0)
            throw new IllegalArgumentException("invalid transfer completion");
        accountBook.assertOwner();
        long routed = accountBook.pendingTransferUser(transferId);
        if (routed != 0 && routed != userId)
            throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        clear();
        this.kind = KIND_TRANSFER_COMPLETE;
        this.accountBook = accountBook;
        this.userId = userId;
        this.transferId = transferId;
        this.completed = routed == 0;
        if (!this.completed) accountBook.dispatchControlLanes(accountBook.topology().accountLaneMask(userId), this);
        return this;
    }

    public AccountCommandService prepareLeverageChange(AccountBook accountBook, IdIndex identities,
                                                       long userId, UpdateLeverageCommand command) {
        clear();
        this.kind = KIND_LEVERAGE;
        this.accountBook = Objects.requireNonNull(accountBook);
        this.leverageKey = leverageKey(accountBook, identities, userId, command);
        this.leverageCommand = command;
        this.symbolId = identities.symbolId(leverageKey.instrumentId());
        accountBook.dispatchControlLanes(accountBook.topology().accountLaneMask(userId), this);
        return this;
    }

    public AccountCommandService preparePositionModeChange(AccountBook accountBook, long userId,
                                                           UpdatePositionModeCommand command) {
        if (accountBook == null || userId <= 0 || command == null)
            throw new IllegalArgumentException("invalid account position mode change");
        accountBook.assertOwner();
        clear();
        this.kind = KIND_POSITION_MODE;
        this.accountBook = accountBook;
        this.userId = userId;
        this.positionModeCommand = command;
        accountBook.dispatchControlLanes(accountBook.topology().accountLaneMask(userId), this);
        return this;
    }

    public AccountCommandService preparePositionMarginAdjustment(AccountBook accountBook, IdIndex identities,
                                                                 long userId, AdjustPositionMarginCommand command) {
        clear();
        this.kind = KIND_POSITION_MARGIN;
        this.accountBook = Objects.requireNonNull(accountBook);
        this.positionKey = positionMarginKey(accountBook, identities, userId, command);
        this.userId = userId;
        this.positionMarginCommand = command;
        this.nextRevision = Math.incrementExact(accountBook.revision());
        accountBook.dispatchControlLanes(accountBook.topology().accountLaneMask(userId), this);
        return this;
    }

    public void clear() {
        kind = 0;
        accountBook = null;
        userId = 0;
        nextRevision = 0;
        completed = false;
        stateChanged = false;
        assetId = 0;
        deltaUnits = 0;
        transferCommand = null;
        changedOnLane = false;
        transferId = 0;
        transferRemoved = false;
        leverageKey = null;
        leverageCommand = null;
        symbolId = 0;
        positionModeCommand = null;
        positionKey = 0;
        positionMarginCommand = null;
    }

    @Override public Object apply(int laneId) {
        return switch (kind) {
            case KIND_BALANCE -> {
                adjustAccountBalance(accountBook, userId, assetId, deltaUnits);
                yield null;
            }
            case KIND_TRANSFER_OUT -> {
                TransferFundsCommand transfer = prepareTransferOut(accountBook, userId, transferCommand);
                if (transfer != null) {
                    accountBook.requireRevisionCapacity();
                    debitTransferAccount(accountBook, userId, assetId, transferCommand.amountUnits());
                    accountBook.putPendingTransfer(transfer);
                    accountBook.incrementCommandRevision();
                    changedOnLane = true;
                }
                yield null;
            }
            case KIND_TRANSFER_COMPLETE -> {
                transferRemoved = completeTransfer(accountBook, userId, transferId);
                yield null;
            }
            case KIND_LEVERAGE -> {
                changedOnLane = updateAccountLeverage(
                        accountBook, accountBook.accountLane(laneId), leverageKey, symbolId,
                        leverageCommand.leveragePpm(), leverageCommand.repriceCrossMargin());
                yield null;
            }
            case KIND_POSITION_MODE -> {
                changedOnLane = updateAccountPositionMode(accountBook, userId, positionModeCommand);
                yield null;
            }
            case KIND_POSITION_MARGIN -> {
                assetId = adjustAccountPositionMargin(accountBook, userId, positionKey, positionMarginCommand);
                yield null;
            }
            default -> throw new IllegalStateException("unknown account control task kind: " + kind);
        };
    }

    @Override public boolean getAsBoolean() {
        accountBook.assertOwner();
        if (completed) return true;
        if (!accountBook.pollControlLanes()) return false;
        switch (kind) {
            case KIND_BALANCE -> {
                accountBook.markBalanceChanged(userId);
                accountBook.setMetadata(accountBook.productLine(), nextRevision);
                stateChanged = true;
            }
            case KIND_TRANSFER_OUT -> {
                if (changedOnLane && accountBook.accountLanesStarted())
                    accountBook.registerPendingTransferRoute(transferCommand.transferId(), userId);
                transferCommand = null;
                stateChanged = true;
            }
            case KIND_TRANSFER_COMPLETE -> {
                if (!transferRemoved) throw new IllegalStateException("pending transfer route has no Lane identity");
                if (accountBook.accountLanesStarted()) accountBook.completePendingTransferRoute(transferId, userId);
                stateChanged = true;
            }
            case KIND_LEVERAGE -> {
                if (changedOnLane) {
                    accountBook.recordLeverageChange(leverageKey, leverageCommand.leveragePpm());
                    accountBook.setMetadata(accountBook.productLine(), Math.incrementExact(accountBook.revision()));
                    stateChanged = true;
                }
            }
            case KIND_POSITION_MODE -> {
                if (changedOnLane) {
                    accountBook.markUserChanged(userId);
                    accountBook.setMetadata(accountBook.productLine(), Math.incrementExact(accountBook.revision()));
                    stateChanged = true;
                }
            }
            case KIND_POSITION_MARGIN -> {
                accountBook.recordUserSettlementChanges(userId, assetId, positionKey);
                accountBook.setMetadata(accountBook.productLine(), nextRevision);
                stateChanged = true;
            }
            default -> throw new IllegalStateException("unknown account control task kind: " + kind);
        }
        completed = true;
        return true;
    }

    private static void adjustAccountBalance(AccountBook accountBook, long userId, int assetId, long deltaUnits) {
        AccountBalance current = accountBook.balance(userId, assetId);
        long currentAvailable = current == null ? 0 : current.availableUnits();
        long nextAvailable = Math.addExact(currentAvailable, deltaUnits);
        if (nextAvailable < 0) throw new IllegalArgumentException("available balance cannot be negative");
        UserAccount user = accountBook.user(userId);
        long userRevision = user == null ? 1 : Math.incrementExact(user.revision());
        var mode = user == null ? com.surprising.aeron.protocol.CorePositionMode.ONE_WAY : user.positionMode();
        AccountBalance initialBalance = current == null ? new AccountBalance(userId, assetId, nextAvailable, 0) : null;
        accountBook.setUserState(userId, userRevision, mode);
        if (initialBalance != null) accountBook.putBalance(initialBalance);
        else accountBook.replaceBalance(userId, assetId, nextAvailable, current.lockedUnits());
    }

    private static TransferFundsCommand prepareTransferOut(AccountBook accountBook, long userId,
                                                           TransferFundsCommand command) {
        validateTransferSource(accountBook, userId, command);
        TransferFundsCommand existing = accountBook.pendingTransfer(command.transferId());
        if (existing != null) {
            if (!existing.equals(command))
                throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer identity contains different data");
            return null;
        }
        if (!accountBook.accountLanesStarted() && !accountBook.hasPendingTransferCapacity())
            throw new CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL", "pending transfer runtime capacity is full");
        return command;
    }

    private static void validateTransferSource(AccountBook accountBook, long userId, TransferFundsCommand command) {
        if (accountBook == null || command == null || userId <= 0)
            throw new IllegalArgumentException("invalid runtime transfer out");
        accountBook.assertOwner();
        if (userId != command.sourceUserId()) throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer sender mismatch");
        if (accountBook.productLine() != command.sourceProductLine())
            throw new CoreStateRejectedException("PRODUCT_LINE_MISMATCH", "transfer source product line mismatch");
    }

    private static void debitTransferAccount(AccountBook accountBook, long userId, int assetId, long amountUnits) {
        AccountBalance balance = accountBook.balance(userId, assetId);
        if (balance == null || balance.availableUnits() < amountUnits)
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "transfer available balance is insufficient");
        UserAccount user = accountBook.requireUser(userId);
        long userRevision = Math.incrementExact(user.revision());
        long nextAvailable = Math.subtractExact(balance.availableUnits(), amountUnits);
        accountBook.setUserState(userId, userRevision, user.positionMode());
        accountBook.replaceBalance(userId, assetId, nextAvailable, balance.lockedUnits());
    }

    public static boolean completeTransfer(AccountBook accountBook, long userId, long transferId) {
        if (accountBook == null || userId <= 0 || transferId <= 0) {
            throw new IllegalArgumentException("invalid runtime transfer completion");
        }
        accountBook.assertOwner();
        if (!accountBook.removePendingTransfer(transferId, userId)) return false;
        accountBook.incrementCommandRevision();
        return true;
    }

    public static boolean updateAccountPositionMode(AccountBook accountBook, long userId,
                                                    UpdatePositionModeCommand command) {
        if (!accountBook.productLine().isDerivative())
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED", "position mode requires derivative product line");
        UserAccount current = accountBook.user(userId);
        var currentMode = current == null ? com.surprising.aeron.protocol.CorePositionMode.ONE_WAY : current.positionMode();
        if (currentMode == command.positionMode()) return false;
        if (accountBook.hasOpenAccountExposure(userId))
            throw new CoreStateRejectedException("POSITION_MODE_SWITCH_BLOCKED", "open positions or orders block position mode update");
        Math.incrementExact(accountBook.revision());
        accountBook.setUserState(userId, current == null ? 1 : Math.incrementExact(current.revision()),
                command.positionMode());
        return true;
    }

    static CoreLeverageKey leverageKey(AccountBook accountBook, IdIndex identities,
                                       long userId, UpdateLeverageCommand command) {
        accountBook.assertOwner();
        if (userId <= 0 || command == null || identities == null)
            throw new IllegalArgumentException("invalid runtime leverage update");
        if (!accountBook.productLine().isDerivative())
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED", "leverage requires derivative product line");
        CoreInstrument instrument = accountBook.instrument(command.instrumentId());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument does not exist");
        if (instrument.contractType().isOption())
            throw new CoreStateRejectedException("OPTION_LEVERAGE_UNSUPPORTED", "non-portfolio option margin is not leverage based");
        if (command.repriceCrossMargin()) {
            int symbolId = identities.symbolId(instrument.instrumentIdValue());
            if (accountBook.systemFunds().fundingProgress(symbolId) != null
                    || accountBook.systemFunds().lifecycleSettlement(symbolId) != 0)
                throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "position settlement blocks margin repricing");
            if (command.leveragePpm() > instrument.maxLeveragePpm())
                throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT", "leverage exceeds instrument maximum");
        }
        long minimumRate = Math.max(instrument.initialMarginRatePpm(),
                CoreContractMath.riskBracket(instrument, 0).initialMarginRatePpm());
        if (CoreContractMath.initialMarginRateFromLeverage(command.leveragePpm()) < minimumRate)
            throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT", "leverage exceeds instrument maximum");
        return new CoreLeverageKey(userId, instrument.instrumentId(), command.marginMode());
    }

    static boolean updateAccountLeverage(AccountBook accountBook, AccountPartition lane, CoreLeverageKey key,
                                         int symbolId, long leveragePpm, boolean repriceCrossMargin) {
        if (repriceCrossMargin) return lowerCrossLeverage(accountBook, key, symbolId, leveragePpm, lane);
        Long current = accountBook.leverage(key);
        if (current != null && current.longValue() == leveragePpm) return false;
        if (accountBook.hasOpenLeverageExposure(key, symbolId))
            throw new CoreStateRejectedException("LEVERAGE_UPDATE_BLOCKED", "open orders or positions exist");
        Math.incrementExact(accountBook.revision());
        accountBook.putLeverage(key, leveragePpm);
        return true;
    }

    private static boolean lowerCrossLeverage(AccountBook accountBook, CoreLeverageKey key,
            int symbolId, long leveragePpm, AccountPartition lane) {
        if (key.marginMode() != com.surprising.aeron.protocol.CoreMarginMode.CROSS)
            throw new CoreStateRejectedException("LEVERAGE_REPRICE_REQUIRES_CROSS", "cross margin is required");
        CoreInstrument instrument = accountBook.instrument(key.instrumentId());
        Long configured = accountBook.leverage(key);
        long previous = configured == null ? instrument.maxLeveragePpm() : configured;
        if (configured != null && previous == leveragePpm) return false;
        if (leveragePpm > previous)
            throw new CoreStateRejectedException("LEVERAGE_REPRICE_INCREASE_BLOCKED", "only lowering leverage is supported");
        var orderIds = lane.activeOrderIds(key.userId());
        if (orderIds != null) {
            var it = orderIds.iterator();
            while (it.hasNext()) {
                var order = lane.orders.get(it.nextValue());
                if (order != null && order.symbolId() == symbolId && order.marginMode() == key.marginMode()
                        && order.status() == com.surprising.aeron.model.CoreOrderStatus.OPEN)
                    throw new CoreStateRejectedException("LEVERAGE_UPDATE_BLOCKED", "cancel open orders before repricing margin");
            }
        }
        Math.incrementExact(accountBook.revision());
        var updatedKeys = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        var positions = new java.util.ArrayList<Position>();
        long additional = 0;
        int assetId = -1;
        var positionKeys = accountBook.positionKeysForUserAndSymbolPrimitive(key.userId(), symbolId);
        for (int i = 0; i < positionKeys.size(); i++) {
            long positionKey = positionKeys.get(i);
            Position position = lane.positions.get(positionKey);
            if (position == null || position.marginMode() != key.marginMode() || position.signedQuantitySteps() == 0) continue;
            if (accountBook.activeLiquidation(key.userId(), symbolId, position.positionSide()) != null)
                throw new CoreStateRejectedException("LEVERAGE_UPDATE_BLOCKED", "liquidation is in progress");
            var mark = accountBook.markPrice(symbolId);
            if (mark == null || mark.markPriceTicks() <= 0)
                throw new CoreStateRejectedException("MARK_PRICE_UNAVAILABLE", "position margin requires a mark price");
            long quantity = Math.absExact(position.signedQuantitySteps());
            long notional = CoreContractMath.riskNotionalUnits(instrument, quantity, mark.markPriceTicks());
            var bracket = CoreContractMath.riskBracket(instrument, notional);
            if (notional > bracket.notionalCapUnits() || leveragePpm > bracket.maxLeveragePpm()
                    || CoreContractMath.initialMarginRateFromLeverage(leveragePpm) < bracket.initialMarginRatePpm())
                throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_RISK_BRACKET", "target leverage exceeds position risk bracket");
            long margin = Math.max(position.positionMarginUnits(), Math.max(
                    CoreContractMath.openingMarginForFill(
                            instrument, position.signedQuantitySteps(), quantity, position.entryPriceTicks(), leveragePpm),
                    CoreContractMath.openingMarginForFill(
                            instrument, position.signedQuantitySteps(), quantity, mark.markPriceTicks(), leveragePpm)));
            additional = Math.addExact(additional, Math.subtractExact(margin, position.positionMarginUnits()));
            if (assetId != -1 && assetId != position.assetId()) throw new IllegalStateException("position settlement asset mismatch");
            assetId = position.assetId();
            updatedKeys.add(positionKey);
            positions.add(new Position(position.userId(), symbolId,
                    assetId, position.marginMode(), position.positionSide(), position.instrument(),
                    position.signedQuantitySteps(), position.entryPriceTicks(), position.entryValueTicks(),
                    position.realizedPnlUnits(), margin));
        }
        if (!positions.isEmpty()) {
            AccountBalance balance = accountBook.balance(key.userId(), assetId);
            if (balance == null || balance.availableUnits() < additional)
                throw new CoreStateRejectedException("INSUFFICIENT_BALANCE", "insufficient available funds for lower leverage");
            long nextAvailable = Math.subtractExact(balance.availableUnits(), additional);
            long nextLocked = Math.addExact(balance.lockedUnits(), additional);
            UserAccount user = accountBook.requireUser(key.userId());
            long userRevision = Math.incrementExact(user.revision());
            accountBook.replaceBalance(key.userId(), assetId, nextAvailable, nextLocked);
            for (int index = 0; index < positions.size(); index++) {
                accountBook.replacePosition(updatedKeys.get(index), positions.get(index));
            }
            accountBook.setUserState(user.userId(), userRevision, user.positionMode());
        }
        accountBook.putLeverage(key, leveragePpm);
        return true;
    }

    static long positionMarginKey(AccountBook accountBook, IdIndex identities,
                                  long userId, AdjustPositionMarginCommand command) {
        if (accountBook == null || identities == null || command == null || userId <= 0)
            throw new IllegalArgumentException("invalid runtime position margin adjustment");
        accountBook.assertOwner();
        if (command.amountUnits() == 0)
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID", "only isolated position margin can be adjusted");
        String instrumentId = InstrumentId.requireValid(command.instrumentId());
        CoreInstrument instrument = accountBook.instrument(instrumentId);
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument does not exist");
        return identities.positionKey(userId, instrument, command.positionSide());
    }

    static int adjustAccountPositionMargin(AccountBook accountBook, long userId, long positionKey,
                                           AdjustPositionMarginCommand command) {
        Position position = accountBook.position(positionKey);
        if (position == null || position.signedQuantitySteps() == 0
                || position.marginMode() != com.surprising.aeron.protocol.CoreMarginMode.ISOLATED || position.positionSide() != command.positionSide())
            throw new CoreStateRejectedException("POSITION_NOT_FOUND", "isolated position does not exist");
        AccountBalance balance = accountBook.balance(userId, position.assetId());
        if (balance == null) throw new IllegalStateException("position margin balance is missing");
        long units = Math.absExact(command.amountUnits());
        long nextMargin;
        long nextAvailable;
        long nextLocked;
        if (command.amountUnits() > 0) {
            if (balance.availableUnits() < units) throw new IllegalArgumentException("insufficient runtime balance");
            nextMargin = Math.addExact(position.positionMarginUnits(), units);
            nextAvailable = Math.subtractExact(balance.availableUnits(), units);
            nextLocked = Math.addExact(balance.lockedUnits(), units);
        } else {
            if (position.positionMarginUnits() < units)
                throw new CoreStateRejectedException("POSITION_MARGIN_INSUFFICIENT", "position margin is insufficient");
            if (balance.lockedUnits() < units) throw new IllegalArgumentException("invalid runtime release");
            nextMargin = Math.subtractExact(position.positionMarginUnits(), units);
            nextAvailable = Math.addExact(balance.availableUnits(), units);
            nextLocked = Math.subtractExact(balance.lockedUnits(), units);
        }
        Position nextPosition = new Position(position.userId(), position.symbolId(), position.assetId(),
                position.marginMode(), position.positionSide(), position.instrument(), position.signedQuantitySteps(),
                position.entryPriceTicks(), position.entryValueTicks(), position.realizedPnlUnits(), nextMargin);
        UserAccount user = accountBook.requireUser(userId);
        long userRevision = Math.incrementExact(user.revision());
        accountBook.replaceBalance(userId, position.assetId(), nextAvailable, nextLocked);
        accountBook.replacePosition(positionKey, nextPosition);
        accountBook.setUserState(userId, userRevision, user.positionMode());
        return position.assetId();
    }

    /** 一个运行时同时推进一项账户控制派发。复用每分片的任务槽，先保存结果和修订增量再发布完成位，Owner 观察到完成位后才读取结果（release/acquire），收齐后再发布变化。 */
    @RequiredArgsConstructor
    public static final class Dispatcher {
        private static final int CACHE_LINE_LONGS = 16;
        /** long 数组元素的 VarHandle，完成发布用 release、Owner 观察用 acquire。 */
        private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);
        private final AccountBook owner;
        private LaneControlTask[] tasks;
        private long[] completedLanes;
        /** 当前控制派发必须完成的分片位集合。 */
        private long requiredLaneMask;
        /** Owner 已 acquire 观察到完成的分片缓存位图。 */
        private long observedCompletedMask;
        /** 当前是否还需要 Owner 收集控制派发结果。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private boolean pending;
        private DirectCommandDispatcher.OperationType operationType;

        private void initializeTasks() {
            if (tasks != null) return;
            int count = owner.topology().accountLaneCount();
            tasks = new LaneControlTask[count];
            completedLanes = new long[count * CACHE_LINE_LONGS];
            for (int laneId = 0; laneId < count; laneId++) tasks[laneId] = new LaneControlTask(laneId);
        }

        public boolean active() {
            AccountPartition lane = owner.currentLaneCommandScope();
            return lane != null && tasks != null && tasks[lane.laneId()].executing;
        }

        public void recordRevisionDelta() {
            AccountPartition lane = owner.currentLaneCommandScope();
            if (lane == null || !tasks[lane.laneId()].executing)
                throw new IllegalStateException("account revision must belong to its control Lane");
            LaneControlTask task = tasks[lane.laneId()];
            task.revisionDelta = Math.incrementExact(task.revisionDelta);
        }

        public String diagnostics() {
            return "pending=" + pending + ",expected=" + Long.toHexString(requiredLaneMask)
                    + ",completed=" + Long.toHexString(completedMask()) + ",operation=" + operationType;
        }

        public void dispatch(long laneMask, DirectCommandDispatcher.OperationType type, IntFunction<Object> operation) {
            owner.assertOwner();
            initializeTasks();
            long validMask = tasks.length == 64 ? -1L : (1L << tasks.length) - 1;
            if (pending || laneMask == 0
                    || (laneMask & ~validMask) != 0 || operation == null || type == null)
                throw new IllegalStateException("invalid control Lane dispatch");
            if (owner.accountLanesStarted()) {
                owner.ensureLaneWorkerCapacity(laneMask);
                owner.releaseOwnerLaneAccess();
            }
            for (LaneControlTask task : tasks) task.clear();
            requiredLaneMask = laneMask;
            observedCompletedMask = 0;
            operationType = type;
            pending = true;
            long lanes = laneMask;
            while (lanes != 0) {
                int laneId = Long.numberOfTrailingZeros(lanes);
                lanes &= lanes - 1;
                LONGS.setRelease(completedLanes, laneId * CACHE_LINE_LONGS, 0L);
                tasks[laneId].operation = operation;
                if (!owner.accountLanesStarted()) {
                    tasks[laneId].execute(owner.accountLane(laneId));
                } else {
                    owner.submitAccountCommand(laneId, tasks[laneId]);
                }
            }
        }

        public boolean poll() {
            owner.assertOwner();
            owner.assertAccountLanesHealthy();
            if (!pending) return true;
            if (completedMask() != requiredLaneMask) return false;
            pending = false;
            long revisionDelta = 0;
            RuntimeException rejection = null;
            long lanes = requiredLaneMask;
            while (lanes != 0) {
                int laneId = Long.numberOfTrailingZeros(lanes);
                lanes &= lanes - 1;
                LaneControlTask task = tasks[laneId];
                revisionDelta = Math.addExact(revisionDelta, task.revisionDelta);
                if (rejection == null) rejection = task.rejection;
            }
            if (rejection != null) throw rejection;
            lanes = requiredLaneMask;
            while (lanes != 0) {
                int laneId = Long.numberOfTrailingZeros(lanes);
                lanes &= lanes - 1;
                owner.flushPublishedChanges(laneId);
            }
            if (revisionDelta != 0)
                owner.setMetadata(owner.productLine(), Math.addExact(owner.revision(), revisionDelta));
            return true;
        }

        public Object result(int laneId) {
            owner.assertOwner();
            if (tasks == null || laneId < 0 || laneId >= tasks.length || (requiredLaneMask & (1L << laneId)) == 0
                    || pending || completedMask() != requiredLaneMask)
                throw new IllegalStateException("Lane result is not ready");
            return tasks[laneId].result;
        }

        private long completedMask() {
            long lanes = requiredLaneMask & ~observedCompletedMask;
            while (lanes != 0) {
                int laneId = Long.numberOfTrailingZeros(lanes);
                lanes &= lanes - 1;
                if ((long) LONGS.getAcquire(completedLanes, laneId * CACHE_LINE_LONGS) != 0)
                    observedCompletedMask |= 1L << laneId;
            }
            return observedCompletedMask;
        }

        @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
        private final class LaneControlTask implements AccountLaneCommand {
            private final int laneId;
            private IntFunction<Object> operation;
            private Object result;
            private RuntimeException rejection;
            private long revisionDelta;
            private boolean executing;

            void clear() {
                operation = null;
                result = null;
                rejection = null;
                revisionDelta = 0;
            }

            @Override public void execute(AccountPartition lane) {
                executing = true;
                try { result = owner.inLaneCommandScope(lane, ignored -> operation.apply(laneId)); }
                catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
                    rejection = failure;
                } finally {
                    owner.laneDelta(laneId).freezeControlValues();
                    executing = false;
                    operation = null;
                }
                LONGS.setRelease(completedLanes, laneId * CACHE_LINE_LONGS, 1L);
            }
        }
    }
}
