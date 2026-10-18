package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.account.TransferRuntime;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.model.AssetBalance;

/** Owns runtime account balances and the pending cross-product transfer lifecycle. */
public final class RuntimeAccountStateTransitions {

    private RuntimeAccountStateTransitions() {
    }

    public static void adjustBalance(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                              long userId, BalanceAdjustmentCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime balance adjustment");
        }
        runtime.assertOwner();
        String asset = AssetBalance.normalizeAsset(command.asset());
        int assetId = identities.assetId(asset);
        long nextRevision = Math.incrementExact(runtime.revision());
        adjustAccountBalance(runtime, userId, assetId, command.deltaUnits());
        runtime.setMetadata(runtime.productLine(), nextRevision);
    }

    /** Only changes the owning account; revision publication belongs to the enclosing command. */
    static void adjustAccountBalance(TradingRuntimeState runtime, long userId, int assetId, long deltaUnits) {
        BalanceRuntime current = runtime.balance(userId, assetId);
        long currentAvailable = current == null ? 0 : current.availableUnits();
        long nextAvailable = Math.addExact(currentAvailable, deltaUnits);
        if (nextAvailable < 0) throw new IllegalArgumentException("available balance cannot be negative");
        UserRuntime user = runtime.user(userId);
        // Complete arithmetic validation before the first account write to avoid partial mutation on overflow.
        long userRevision = user == null ? 1 : Math.incrementExact(user.revision());
        var mode = user == null ? com.surprising.aeron.protocol.CorePositionMode.ONE_WAY : user.positionMode();
        BalanceRuntime initialBalance = current == null ? new BalanceRuntime(userId, assetId, nextAvailable, 0) : null;
        runtime.setUserState(userId, userRevision, mode);
        if (initialBalance != null) runtime.putBalance(initialBalance);
        else runtime.replaceBalance(userId, assetId, nextAvailable, current.lockedUnits());
    }

    public static boolean transferOut(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                               long userId, TransferFundsCommand command) {
        if (identities == null) throw new IllegalArgumentException("transfer identities are required");
        TransferRuntime transfer = prepareTransferOut(runtime, userId, command);
        if (transfer == null) return false;
        int assetId = identities.assetId(command.asset());
        runtime.requireRevisionCapacity();
        debitTransferAccount(runtime, userId, assetId, command.amountUnits());
        runtime.putPendingTransfer(transfer);
        runtime.incrementCommandRevision();
        return true;
    }

    /** Validates transfer identity and idempotency without reading mutable balance state. */
    static TransferRuntime prepareTransferOut(TradingRuntimeState runtime, long userId,
                                              TransferFundsCommand command) {
        validateTransferSource(runtime, userId, command);
        TransferRuntime existing = runtime.pendingTransfer(command.transferId());
        if (existing != null) {
            if (existing.userId() != userId || !existing.command().equals(command))
                throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer identity contains different data");
            return null;
        }
        if (!runtime.accountLanesStarted && !runtime.hasPendingTransferCapacity())
            throw new CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL", "pending transfer runtime capacity is full");
        return new TransferRuntime(userId, command);
    }

    static void validateTransferSource(TradingRuntimeState runtime, long userId, TransferFundsCommand command) {
        if (runtime == null || command == null || userId <= 0)
            throw new IllegalArgumentException("invalid runtime transfer out");
        runtime.assertOwner();
        if (userId != command.sourceUserId()) throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer sender mismatch");
        if (runtime.productLine() != command.sourceProductLine())
            throw new CoreStateRejectedException("PRODUCT_LINE_MISMATCH", "transfer source product line mismatch");
    }

    /** Single account write for a transfer out; locked balance and other products are not spendable. */
    static void debitTransferAccount(TradingRuntimeState runtime, long userId, int assetId, long amountUnits) {
        BalanceRuntime balance = runtime.balance(userId, assetId);
        if (balance == null || balance.availableUnits() < amountUnits)
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "transfer available balance is insufficient");
        UserRuntime user = runtime.requireUser(userId);
        long userRevision = Math.incrementExact(user.revision());
        long nextAvailable = Math.subtractExact(balance.availableUnits(), amountUnits);
        runtime.setUserState(userId, userRevision, user.positionMode());
        runtime.replaceBalance(userId, assetId, nextAvailable, balance.lockedUnits());
    }

    public static void transferIn(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                           long userId, TransferFundsCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime transfer in");
        }
        runtime.assertOwner();
        if (userId != command.targetUserId()) throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer recipient mismatch");
        if (runtime.productLine() != command.targetProductLine()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_MISMATCH", "transfer target product line mismatch");
        }
        adjustBalance(runtime, identities, userId,
                new BalanceAdjustmentCommand(command.asset(), command.amountUnits()));
    }

    public static boolean completeTransfer(TradingRuntimeState runtime, long userId, long transferId) {
        if (runtime == null || userId <= 0 || transferId <= 0) {
            throw new IllegalArgumentException("invalid runtime transfer completion");
        }
        runtime.assertOwner();
        if (!runtime.removePendingTransfer(transferId, userId)) return false;
        runtime.incrementCommandRevision();
        return true;
    }
}
