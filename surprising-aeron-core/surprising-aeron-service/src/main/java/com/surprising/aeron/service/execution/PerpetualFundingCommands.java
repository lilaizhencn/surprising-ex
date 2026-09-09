package com.surprising.aeron.service.execution;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.state.RuntimePerpetualFundingProcessor;

/** 永续合约资金费命令；沿用 U 本位和币本位各自结算规则。 */
final class PerpetualFundingCommands {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingCoreRuntime owner;

    PerpetualFundingCommands(TradingCoreRuntime owner) { this.owner = owner; }

    void executeApplyFunding(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeApplyFunding(message.payloadUnsafe());
        Iterable<Long> indexedUserIds = owner.positionUserIndex.usersAfter(command.symbol(), command.cursorUserId());
        if (owner.runtimeState.asynchronousCommands()) {
            var work = RuntimePerpetualFundingProcessor.prepare(command, indexedUserIds,
                    message.header().commandId(), owner.runtimeState, owner.identities);
            owner.deferControl(() -> {
                if (!work.poll()) return false;
                if (!owner.runtimeState.tryAcquireOwnerLaneAccess()) return false;
                complete(work.result());
                return true;
            });
            return;
        }
        var result = RuntimePerpetualFundingProcessor.applyRuntime(command, indexedUserIds,
                message.header().commandId(), owner.runtimeState, owner.identities);
        complete(result);
    }

    private void complete(RuntimePerpetualFundingProcessor.FundingResult result) {
        if (result.state() != owner.runtimeState) {
            throw new IllegalStateException("funding processor replaced authoritative runtime state");
        }
        owner.commits.requestCommitPublication();
        owner.resultBuilder.commandFundingProgress = result.progress();
        owner.resultBuilder.commandChangedUserIds = result.payments().stream()
                .map(com.surprising.aeron.protocol.CoreFundingPaymentView::userId).distinct().toList();
    }
}
