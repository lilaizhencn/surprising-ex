package com.surprising.aeron.service.adl.command;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.adl.RuntimeAdlExecution;
import com.surprising.aeron.service.core.TradingCoreRuntime;

/**
 * 自动减仓（ADL）执行入口：解码目标持仓与补亏要求，登记目标用户响应提示，再进入原账户及账本执行路径；目标持仓资格、补亏金额和完成状态仍由 ADL 业务执行器核对。
 */
public final class AdlCommands {
    /** 本产品线交易 Owner，提供 ADL 复用任务、账户身份和提交入口。 */
    private final TradingCoreRuntime owner;

    /**
     * 保存非空 Owner 引用，不提前创建或启动 ADL 工作。
     * @param owner 本产品线交易 Owner，提供权威运行时、身份表、控制槽和提交入口
     */
    public AdlCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 解码请求后登记目标用户提示，准备复用工作并交给原控制槽。提示先登记不代表目标持仓已经减仓成功。
     * @param message 当前借用的请求头与载荷，解码期间保持缓冲有效
     */
    public void executeExecuteAdl(CoreMessage message) {
        // 解码得到的强平 ID、目标用户及持仓、价格序列、减仓量和补亏要求
        var command = TradingCommandCodec.decodeExecuteAdl(message.payloadUnsafe());
        owner.resultBuilder.setSingleChangedUser(command.targetUserId());
        // 复用槽中准备好的 ADL 跨账户工作，真正完成由原控制任务继续轮询
        var work = RuntimeAdlExecution.begin(owner.directCommand.reusableAdlWork(),
                command, owner.runtimeState(), owner.identities());
        owner.directCommand.deferAdlControl(work);
    }

}
