package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.state.model.AssetBalance;

/** Owns runtime account balances and the pending cross-product transfer lifecycle. */
final class RuntimeAccountStateTransitions {

    private RuntimeAccountStateTransitions() {
    }

    static void adjustBalance(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
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
        UserRuntime nextUser = new UserRuntime(runtime.productLine(), userId,
                user == null ? 1 : Math.incrementExact(user.revision()),
                user == null ? com.surprising.aeron.protocol.CorePositionMode.ONE_WAY : user.positionMode());
        BalanceRuntime nextBalance = new BalanceRuntime(userId, assetId, nextAvailable,
                current == null ? 0 : current.lockedUnits());
        runtime.putUser(nextUser);
        if (current == null) runtime.putBalance(nextBalance);
        else runtime.replaceBalance(nextBalance);
    }

    static boolean transferOut(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                               long userId, TransferFundsCommand command) {
        if (identities == null) throw new IllegalArgumentException("transfer identities are required");
        TransferRuntime transfer = prepareTransferOut(runtime, userId, command);
        if (transfer == null) return false;
        int assetId = identities.assetId(command.asset());
        long nextRevision = Math.incrementExact(runtime.revision());
        debitTransferAccount(runtime, userId, assetId, command.amountUnits());
        runtime.putPendingTransfer(transfer);
        runtime.setMetadata(runtime.productLine(), nextRevision);
        return true;
    }

    /** Validates transfer identity and idempotency without reading mutable balance state. */
    static TransferRuntime prepareTransferOut(TradingRuntimeState runtime, long userId,
                                              TransferFundsCommand command) {
        if (runtime == null || command == null || userId <= 0)
            throw new IllegalArgumentException("invalid runtime transfer out");
        runtime.assertOwner();
        if (runtime.productLine() != command.sourceProductLine())
            throw new CoreStateRejectedException("PRODUCT_LINE_MISMATCH", "transfer source product line mismatch");
        TransferRuntime transfer = new TransferRuntime(userId, command);
        TransferRuntime existing = runtime.pendingTransfer(command.transferId());
        if (existing != null) {
            if (!existing.equals(transfer))
                throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer identity contains different data");
            return null;
        }
        if (!runtime.hasPendingTransferCapacity())
            throw new CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL", "pending transfer runtime capacity is full");
        return transfer;
    }

    /** Single account write for a transfer out; locked balance and other products are not spendable. */
    static void debitTransferAccount(TradingRuntimeState runtime, long userId, int assetId, long amountUnits) {
        BalanceRuntime balance = runtime.balance(userId, assetId);
        if (balance == null || balance.availableUnits() < amountUnits)
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "transfer available balance is insufficient");
        UserRuntime user = runtime.requireUser(userId);
        UserRuntime advanced = new UserRuntime(runtime.productLine(), userId,
                Math.incrementExact(user.revision()), user.positionMode());
        BalanceRuntime debited = new BalanceRuntime(userId, assetId,
                Math.subtractExact(balance.availableUnits(), amountUnits), balance.lockedUnits());
        runtime.putUser(advanced);
        runtime.replaceBalance(debited);
    }

    static void transferIn(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                           long userId, TransferFundsCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime transfer in");
        }
        runtime.assertOwner();
        if (runtime.productLine() != command.targetProductLine()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_MISMATCH", "transfer target product line mismatch");
        }
        adjustBalance(runtime, identities, userId,
                new BalanceAdjustmentCommand(command.asset(), command.amountUnits()));
    }

    static boolean completeTransfer(TradingRuntimeState runtime, long userId, long transferId) {
        if (runtime == null || userId <= 0 || transferId <= 0) {
            throw new IllegalArgumentException("invalid runtime transfer completion");
        }
        runtime.assertOwner();
        if (!runtime.removePendingTransfer(transferId, userId)) return false;
        runtime.incrementCommandRevision();
        return true;
    }
}
