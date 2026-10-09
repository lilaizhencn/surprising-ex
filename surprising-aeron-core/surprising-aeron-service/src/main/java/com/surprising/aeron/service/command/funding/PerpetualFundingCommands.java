package com.surprising.aeron.service.command.funding;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.state.RuntimePerpetualFundingProcessor;

/** 永续合约资金费命令；沿用 U 本位和币本位各自结算规则。 */
public final class PerpetualFundingCommands {
    private final FundingCommandContext owner;
    // Set only by the separate committed replay. Live asynchronous owner/Lane execution
    // has no export listener and does not acquire a database or publish history here.
    private java.util.function.Consumer<RuntimePerpetualFundingProcessor.FundingResult> replayObserver;

    public PerpetualFundingCommands(FundingCommandContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void observeReplayPayments(java.util.function.Consumer<RuntimePerpetualFundingProcessor.FundingResult> observer) {
        this.replayObserver = java.util.Objects.requireNonNull(observer);
    }

    public void executeApplyFunding(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeApplyFunding(message.payloadUnsafe());
        Iterable<Long> indexedUserIds = owner.positionUserIndex().usersAfter(command.instrumentId(), command.cursorUserId());
        if (owner.asynchronousCommands()) {
            var work = RuntimePerpetualFundingProcessor.prepare(owner.reusableFundingWork(), command, indexedUserIds,
                    message.header().commandId(), owner.runtimeState(), owner.identities());
            owner.deferFundingControl(work);
            return;
        }
        var result = RuntimePerpetualFundingProcessor.applyRuntime(command, indexedUserIds,
                message.header().commandId(), owner.runtimeState(), owner.identities());
        complete(result);
        if (replayObserver != null) replayObserver.accept(result);
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
