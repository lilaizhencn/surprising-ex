package com.surprising.aeron.service.insurance.command;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.treasury.RuntimeInsuranceFundStateTransitions;

/**
 * 保险基金调整入口，解码后使用原财库转换或可复用异步控制任务；发布请求与财务任务真正完成分开管理。
 */
public final class InsuranceFundCommands {
    /** 本产品线交易 Owner，提供原财库、身份表及保险调整控制槽。 */
    private final TradingCoreRuntime owner;

    /**
     * 保存非空 Owner 引用，复用其保险调整控制槽。
     * @param owner 唯一交易 Owner 入口，沿用当前命令与提交边界
     */
    public InsuranceFundCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 异步时准备可复用保险基金调整并登记控制续步，冷路径直接应用原财库转换；两条路径都请求发布，最终成功仍等待控制完成。
     * @param message 本次借用的原命令及载荷，调用期间保持缓冲有效
     */
    public void executeAdjustInsuranceFund(CoreMessage message) {
        // 本次解出的保险基金资产和调整数量
        var command = TradingCommandCodec.decodeAdjustInsuranceFund(message.payloadUnsafe());
        if (owner.asynchronousCommands()) {
            owner.directCommand.deferControl(RuntimeInsuranceFundStateTransitions.prepare(
                    owner.runtimeState(), owner.identities(), command));
        } else {
            RuntimeInsuranceFundStateTransitions.adjust(owner.runtimeState(), owner.identities(), command);
        }
        owner.requestCommitPublication();
    }
}
