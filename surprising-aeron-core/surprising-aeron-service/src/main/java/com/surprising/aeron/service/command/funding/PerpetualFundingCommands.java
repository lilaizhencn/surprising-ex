package com.surprising.aeron.service.command.funding;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.state.RuntimePerpetualFundingProcessor;

/** 永续合约资金费命令；沿用 U 本位和币本位各自结算规则。 */
public final class PerpetualFundingCommands {
    private final FundingCommandContext owner;
    /** A single owner-thread callback is reused for all asynchronous funding pages. */
    private final FundingContinuation continuation = new FundingContinuation();
    /** Lazily initialized after the runtime has been constructed; its lane buffers are reused. */
    private RuntimePerpetualFundingProcessor.FundingWork asyncWork;

    public PerpetualFundingCommands(FundingCommandContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void executeApplyFunding(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeApplyFunding(message.payloadUnsafe());
        Iterable<Long> indexedUserIds = owner.positionUserIndex().usersAfter(command.symbol(), command.cursorUserId());
        if (owner.asynchronousCommands()) {
            asyncWork = RuntimePerpetualFundingProcessor.prepare(asyncWork, command, indexedUserIds,
                    message.header().commandId(), owner.runtimeState(), owner.identities());
            continuation.prepare(asyncWork);
            owner.deferControl(continuation);
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

    /** Reusable direct-command continuation; no per-page lambda allocation. */
    private final class FundingContinuation implements java.util.function.BooleanSupplier {
        private RuntimePerpetualFundingProcessor.FundingWork work;

        void prepare(RuntimePerpetualFundingProcessor.FundingWork work) {
            this.work = java.util.Objects.requireNonNull(work);
        }

        @Override public boolean getAsBoolean() {
            if (!work.poll()) return false;
            complete(work.result());
            return true;
        }
    }
}
