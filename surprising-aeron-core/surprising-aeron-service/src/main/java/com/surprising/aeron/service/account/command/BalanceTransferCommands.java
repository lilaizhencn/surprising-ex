package com.surprising.aeron.service.account.command;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.core.TradingCoreRuntime;

/**
 * 余额调整、转出、转入与完成划转的命令入口：解码请求并交给原账户控制槽。请求 UUID、长期资金身份及来源序号的去重由外层运行时和实际资金路径处理。
 */
public final class BalanceTransferCommands {
    /** 持有当前资金命令上下文、账户运行时、控制槽和发布入口的交易 Owner。 */
    private final TradingCoreRuntime owner;

    /**
     * 要求 Owner 非空并保存引用，不启动账户或账本资源。
     * @param owner 唯一交易 Owner 入口，沿用当前命令与提交边界
     */
    public BalanceTransferCommands(TradingCoreRuntime owner) {
        if (owner == null) throw new IllegalArgumentException("owner context is required");
        this.owner = owner;
    }

    /**
     * 预先登记响应用户提示，解码余额调整后交给统一分流；用户提示不代表账户已经修改成功。
     * @param message 本次借用的原命令及载荷，调用期间保持缓冲有效
     */
    public void executeAdjustBalance(CoreMessage message) {
        owner.resultBuilder.setSingleChangedUser(message.header().userId());
        var command = TradingCommandCodec.decodeBalanceAdjustment(message.payloadUnsafe());
        owner.directCommand.deferBalanceAdjustment(message.header().userId(), command.asset(), command.deltaUnits());
    }

    /**
     * 解码带原用户上下文的划转，交给源账户控制槽执行并收集。
     * @param message 本次借用的原命令及载荷，调用期间保持缓冲有效
     */
    public void executeTransferOut(CoreMessage message) {
        owner.resultBuilder.setSingleChangedUser(message.header().userId());
        // 载荷解出的划转参数，后续沿用源/目标用户及产品线信息
        var command = TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe());
        owner.directCommand.deferTransferOut(message.header().userId(), command);
    }

    /**
     * 先核对收款用户及目标产品线，再以划转金额进入统一余额调整；不在这里重复执行转出账户扣款。
     * @param message 本次借用的原命令及载荷，调用期间保持缓冲有效
     */
    public void executeTransferIn(CoreMessage message) {
        owner.resultBuilder.setSingleChangedUser(message.header().userId());
        // 载荷解出的划转参数，后续沿用源/目标用户及产品线信息
        var command = TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe());
        if (message.header().userId() != command.targetUserId())
            throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer recipient mismatch");
        if (owner.runtimeState().productLine() != command.targetProductLine())
            throw new CoreStateRejectedException(
                    "PRODUCT_LINE_MISMATCH", "transfer target product line mismatch");
        owner.directCommand.deferBalanceAdjustment(message.header().userId(), command.asset(), command.amountUnits());
    }

    /**
     * 解码划转身份，准备所属账户的完成划转续步。
     * @param message 本次借用的原命令及载荷，调用期间保持缓冲有效
     */
    public void executeCompleteTransfer(CoreMessage message) {
        // 本次需要完成的划转业务 ID
        long id = TradingCommandCodec.decodeCompleteTransfer(message.payloadUnsafe()).transferId();
        owner.directCommand.deferTransferComplete(message.header().userId(), id);
    }
}
