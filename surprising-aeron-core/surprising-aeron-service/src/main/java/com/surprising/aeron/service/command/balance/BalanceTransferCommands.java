package com.surprising.aeron.service.command.balance;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.state.AccountBalanceAdjustment;
import com.surprising.aeron.service.state.AccountTransferOut;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.RuntimeAccountStateTransitions;

/**
 * 余额调整与资金转入转出命令；维护转账幂等状态。
 *
 * @param owner 唯一 owner；仅在其线程访问共享交易状态和提交边界。
 */
public record BalanceTransferCommands(BalanceCommandContext owner) {

    public BalanceTransferCommands {
        if (owner == null) throw new IllegalArgumentException("owner context is required");
    }

    public void executeAdjustBalance(CoreMessage message, long clusterTimestamp) {
        owner.setSingleChangedUser(message.header().userId());
        adjustBalance(message.header().userId(), TradingCommandCodec.decodeBalanceAdjustment(message.payloadUnsafe()));
    }

    private void adjustBalance(long userId, BalanceAdjustmentCommand command) {
        if (owner.asynchronousCommands()) {
            var work = AccountBalanceAdjustment.prepare(owner.reusableBalanceAdjustment(),
                    owner.runtimeState(), owner.identities(), userId, command);
            owner.deferBalanceAdjustmentControl(work);
        } else {
            RuntimeAccountStateTransitions.adjustBalance(owner.runtimeState(), owner.identities(), userId, command);
            owner.requestCommitPublication();
        }
    }

    public void executeTransferOut(CoreMessage message, long clusterTimestamp) {
        owner.setSingleChangedUser(message.header().userId());
        var command = TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe());
        if (owner.asynchronousCommands()) {
            var work = AccountTransferOut.prepare(owner.reusableTransferOut(), owner.runtimeState(),
                    owner.identities(), message.header().userId(), command);
            owner.deferTransferOutControl(work);
        } else {
            RuntimeAccountStateTransitions.transferOut(owner.runtimeState(), owner.identities(), message.header().userId(), command);
            completeTransferPublication();
        }
    }

    private void completeTransferPublication() {
        owner.refreshTransferHash();
        owner.requestCommitPublication();
    }

    public void executeTransferIn(CoreMessage message, long clusterTimestamp) {
        owner.setSingleChangedUser(message.header().userId());
        var command = TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe());
        if (owner.runtimeState().productLine() != command.targetProductLine())
            throw new CoreStateRejectedException(
                    "PRODUCT_LINE_MISMATCH", "transfer target product line mismatch");
        adjustBalance(message.header().userId(), new BalanceAdjustmentCommand(
                command.asset(), command.amountUnits()));
    }

    public void executeCompleteTransfer(CoreMessage message, long clusterTimestamp) {
        RuntimeAccountStateTransitions.completeTransfer(owner.runtimeState(), message.header().userId(),
                TradingCommandCodec.decodeCompleteTransfer(message.payloadUnsafe()).transferId());
        owner.refreshTransferHash();
        owner.requestCommitPublication();
    }
}
