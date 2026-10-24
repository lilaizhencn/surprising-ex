package com.surprising.aeron.service.liquidation.command;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.liquidation.RuntimeLiquidationResolution;

/**
 * 强平命令的状态入口：交易编排先完成计划、价格、游标及 Matcher 撤单检查，再把真实撤单结果交给此处处理持仓和资金。分段撤单推进与保险等结果确认仍沿用各自原命令的提交边界。
 */
public final class LiquidationCommands {
    /** 本产品线唯一交易 Owner，持有账户运行时、活跃强平集合及控制槽。 */
    private final TradingCoreRuntime owner;

    /**
     * 保存非空 Owner 引用，复用其强平控制任务和提交边界。
     * @param owner 本产品线交易 Owner，提供权威运行时、身份表、控制槽和提交入口
     */
    public LiquidationCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 解码强平结果确认，准备原结果处理任务并交接控制槽。保险分配、状态合法性和剩余亏空由结果处理器决定。
     * @param message 当前借用的请求头与载荷，解码期间保持缓冲有效
     */
    public void executeResolveLiquidation(CoreMessage message) {
        // 解码得到的强平任务身份、确认方式及需要核对的覆盖金额
        var command = TradingCommandCodec.decodeResolveLiquidation(message.payloadUnsafe());
        // 按原运行时及活跃强平集合准备的可续步结果确认任务，完成条件由控制槽继续轮询
        var work = RuntimeLiquidationResolution.begin(owner.directCommand.reusableLiquidationResolutionWork(), command,
                owner.runtimeState(), owner.identities(), owner.activeLiquidationIds());
        owner.directCommand.deferLiquidationResolutionControl(work);
    }

}
