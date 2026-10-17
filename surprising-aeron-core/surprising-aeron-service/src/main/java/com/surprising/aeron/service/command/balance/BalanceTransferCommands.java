package com.surprising.aeron.service.command.balance;

import com.surprising.aeron.service.orchestration.TradingCoreRuntime;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.RuntimeAccountStateTransitions;

/**
 * 余额调整与资金转入转出命令；维护转账幂等状态。
 *
 * @param owner 唯一 owner；仅在其线程访问共享交易状态和提交边界。
 */
public final class BalanceTransferCommands {
    private final TradingCoreRuntime owner;

    public BalanceTransferCommands(TradingCoreRuntime owner) {
        if (owner == null) throw new IllegalArgumentException("owner context is required");
        this.owner = owner;
    }

    public void executeAdjustBalance(CoreMessage message, long clusterTimestamp) {
        owner.setSingleChangedUser(message.header().userId());
        adjustBalance(message.header().userId(), TradingCommandCodec.decodeBalanceAdjustment(message.payloadUnsafe()));
    }

    private void adjustBalance(long userId, BalanceAdjustmentCommand command) {
        if (owner.asynchronousCommands()) {
            owner.deferBalanceAdjustment(userId, command);
        } else {
            RuntimeAccountStateTransitions.adjustBalance(owner.runtimeState(), owner.identities(), userId, command);
            owner.requestCommitPublication();
        }
    }

    public void executeTransferOut(CoreMessage message, long clusterTimestamp) {
        owner.setSingleChangedUser(message.header().userId());
        var command = TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe(), message.header().userId());
        if (owner.asynchronousCommands()) {
            owner.deferTransferOut(message.header().userId(), command);
        } else {
            RuntimeAccountStateTransitions.transferOut(owner.runtimeState(), owner.identities(), message.header().userId(), command);
            completeTransferPublication();
        }
    }

    private void completeTransferPublication() {
        owner.requestCommitPublication();
    }

    public void executeTransferIn(CoreMessage message, long clusterTimestamp) {
        owner.setSingleChangedUser(message.header().userId());
        var command = TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe(), message.header().userId());
        if (message.header().userId() != command.targetUserId())
            throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer recipient mismatch");
        if (owner.runtimeState().productLine() != command.targetProductLine())
            throw new CoreStateRejectedException(
                    "PRODUCT_LINE_MISMATCH", "transfer target product line mismatch");
        adjustBalance(message.header().userId(), new BalanceAdjustmentCommand(
                command.asset(), command.amountUnits()));
    }

    public void executeCompleteTransfer(CoreMessage message, long clusterTimestamp) {
        long id = TradingCommandCodec.decodeCompleteTransfer(message.payloadUnsafe()).transferId();
        if (owner.asynchronousCommands()) {
            owner.deferTransferComplete(message.header().userId(), id);
        } else {
            RuntimeAccountStateTransitions.completeTransfer(owner.runtimeState(), message.header().userId(), id);
            owner.requestCommitPublication();
        }
    }
}
