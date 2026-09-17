package com.surprising.aeron.service.command.funding;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.state.RuntimePerpetualFundingProcessor;

/** 永续合约资金费命令；沿用 U 本位和币本位各自结算规则。 */
public final class PerpetualFundingCommands {
    private final FundingCommandContext owner;

    public PerpetualFundingCommands(FundingCommandContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void executeApplyFunding(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeApplyFunding(message.payloadUnsafe());
        Iterable<Long> indexedUserIds = owner.positionUserIndex().usersAfter(command.symbol(), command.cursorUserId());
        if (owner.asynchronousCommands()) {
            var work = RuntimePerpetualFundingProcessor.prepare(owner.reusableFundingWork(), command, indexedUserIds,
                    message.header().commandId(), owner.runtimeState(), owner.identities());
            owner.deferFundingControl(work);
            return;
        }
        var result = RuntimePerpetualFundingProcessor.applyRuntime(command, indexedUserIds,
                message.header().commandId(), owner.runtimeState(), owner.identities());
        complete(result);
    }

    private void complete(RuntimePerpetualFundingProcessor.FundingResult result) {
        if (result.state() != owner.runtimeState()) {
            throw new IllegalStateException("funding processor replaced authoritative runtime state");
        }
        owner.requestCommitPublication();
        owner.setCommandFundingProgress(result.progress());
        owner.addChangedUsersFromFundingPayments(result.payments());
    }

}
