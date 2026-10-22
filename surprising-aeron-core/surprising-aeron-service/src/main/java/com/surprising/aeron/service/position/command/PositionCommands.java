package com.surprising.aeron.service.position.command;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.account.DerivativeAccountCommandProcessor;
import com.surprising.aeron.service.core.TradingCoreRuntime;

/**
 * 衍生品持仓模式与持仓保证金入口：按用户登记响应提示，将请求交给直接控制槽或原账户转换；是否允许修改由账户业务规则校验。
 */
public final class PositionCommands {
    /** 本产品线交易 Owner，提供用户上下文、控制槽和提交发布入口。 */
    private final TradingCoreRuntime owner;

    /**
     * 保存非空 Owner 引用，不自行启动账户任务。
     * @param owner 本产品线交易 Owner，提供命令上下文、权威状态与提交入口
     */
    public PositionCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 解码单向或双向持仓模式；异步路径保留原修订进入控制续步，冷路径仅在模式实际改变时请求发布。
     * @param message 当前借用的请求头和载荷，解码完成前保持缓冲有效
     */
    public void executeUpdatePositionMode(CoreMessage message) {
        // 请求头指定的持仓所属用户，先登记为响应提示
        long userId = message.header().userId();
        owner.resultBuilder.setSingleChangedUser(userId);
        // 本次解出的单向或双向持仓模式
        var command = TradingCommandCodec.decodeUpdatePositionMode(message.payloadUnsafe());
        if (owner.asynchronousCommands()) {
            // 持仓模式变更前的业务修订，交给异步完成逻辑判断是否变化
            long beforeRevision = owner.runtimeState().revision();
            owner.directCommand.deferPositionModeChange(owner, userId, command, beforeRevision);
        } else if (DerivativeAccountCommandProcessor.updatePositionMode(owner.runtimeState(), userId, command)) {
            owner.requestCommitPublication();
        }
    }

    /**
     * 解码持仓保证金增减；异步路径准备原控制续步，冷路径完成保证金转换后请求发布。具体资金和持仓校验留在账户处理器。
     * @param message 当前借用的请求头和载荷，解码完成前保持缓冲有效
     */
    public void executeAdjustPositionMargin(CoreMessage message) {
        // 请求头指定的持仓所属用户，先登记为响应提示
        long userId = message.header().userId();
        owner.resultBuilder.setSingleChangedUser(userId);
        // 本次解出的标的、保证金模式、持仓方向及保证金增减数量
        var command = TradingCommandCodec.decodeAdjustPositionMargin(message.payloadUnsafe());
        if (owner.asynchronousCommands()) {
            owner.directCommand.deferPositionMarginAdjustment(owner, userId, command);
        } else {
            DerivativeAccountCommandProcessor.adjustPositionMargin(
                    owner.runtimeState(), owner.identities(), userId, command);
            owner.requestCommitPublication();
        }
    }
}
