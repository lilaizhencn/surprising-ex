package com.surprising.aeron.service.position.command;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.core.TradingCoreRuntime;

/**
 * 衍生品杠杆变更入口：登记响应用户、解码，再选择异步控制槽或冷账户转换。杠杆上限、持仓补充保证金及业务拒绝由现有账户处理器执行。
 */
public final class LeverageCommands {
    /** 拥有当前账户控制槽、身份表和发布边界的交易 Owner。 */
    private final TradingCoreRuntime owner;

    /**
     * 保存非空 Owner 引用，复用已有直接控制槽。
     * @param owner 本产品线交易 Owner，提供命令上下文、权威状态与提交入口
     */
    public LeverageCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 准备杠杆控制续步，只有实际改变状态才请求发布。响应用户提示先于校验登记，不表示变更已成功。
     * @param message 当前借用的请求头和载荷，解码完成前保持缓冲有效
     */
    public void executeUpdateLeverage(CoreMessage message) {
        // 请求头指定的杠杆所属用户，同时用于响应提示
        long userId = message.header().userId();
        owner.resultBuilder.setSingleChangedUser(userId);
        // 解码后的标的、保证金模式、目标杠杆及持仓重计要求
        var command = TradingCommandCodec.decodeUpdateLeverage(message.payloadUnsafe());
        owner.directCommand.deferLeverageChange(userId, command);
    }
}
