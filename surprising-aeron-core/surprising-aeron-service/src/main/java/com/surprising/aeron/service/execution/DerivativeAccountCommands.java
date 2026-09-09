package com.surprising.aeron.service.execution;

import com.surprising.aeron.service.state.DerivativeAccountCommandProcessor;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import java.util.List;

/** 衍生品保证金和杠杆命令；不处理现货资金冻结。 */
final class DerivativeAccountCommands {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingCoreRuntime owner;

    DerivativeAccountCommands(TradingCoreRuntime owner) { this.owner = owner; }

    void executeAdjustPositionMargin(CoreMessage message, long clusterTimestamp) {
        owner.resultBuilder.commandChangedUserIds = List.of(message.header().userId());
        DerivativeAccountCommandProcessor.adjustPositionMargin(owner.runtimeState, owner.identities,
                message.header().userId(),
                TradingCommandCodec.decodeAdjustPositionMargin(message.payloadUnsafe()));
        owner.commits.requestCommitPublication();
    }

    void executeUpdateLeverage(CoreMessage message, long clusterTimestamp) {
        owner.resultBuilder.commandChangedUserIds = List.of(message.header().userId());
        if (DerivativeAccountCommandProcessor.updateLeverage(owner.runtimeState, owner.identities,
                message.header().userId(),
                TradingCommandCodec.decodeUpdateLeverage(message.payloadUnsafe()))) {
            owner.commits.requestCommitPublication();
        }
    }
}
