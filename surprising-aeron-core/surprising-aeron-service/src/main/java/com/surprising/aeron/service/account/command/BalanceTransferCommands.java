package com.surprising.aeron.service.account.command;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.account.RuntimeAccountStateTransitions;
import com.surprising.aeron.service.core.TradingCoreRuntime;

/**
 * 余额调整、转出、转入与完成划转的命令入口：解码请求并选择现有冷账户转换或异步控制槽。请求 UUID、长期资金身份及来源序号的去重由外层运行时和实际资金路径处理。
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
     * @param clusterTimestamp 原命令业务时间参数，本入口当前不直接读取
     */
    public void executeAdjustBalance(CoreMessage message, long clusterTimestamp) {
        owner.resultBuilder.setSingleChangedUser(message.header().userId());
        adjustBalance(message.header().userId(), TradingCommandCodec.decodeBalanceAdjustment(message.payloadUnsafe()));
    }

    /**
     * 异步执行时准备直接余额控制续步，冷执行时应用原账户转换并要求提交发布。
     * @param userId 需要调整的账户用户身份
     * @param command 已解码的资产及余额调整数量
     */
    private void adjustBalance(long userId, BalanceAdjustmentCommand command) {
        if (owner.asynchronousCommands()) {
            owner.directCommand.deferBalanceAdjustment(userId, command);
        } else {
            RuntimeAccountStateTransitions.adjustBalance(owner.runtimeState(), owner.identities(), userId, command);
            owner.requestCommitPublication();
        }
    }

    /**
     * 解码带原用户上下文的划转，异步路径交给转出控制槽，冷路径执行原转出转换后请求发布。
     * @param message 本次借用的原命令及载荷，调用期间保持缓冲有效
     * @param clusterTimestamp 原命令业务时间参数，本入口当前不直接读取
     */
    public void executeTransferOut(CoreMessage message, long clusterTimestamp) {
        owner.resultBuilder.setSingleChangedUser(message.header().userId());
        // 载荷解出的划转参数，后续沿用源/目标用户及产品线信息
        var command = TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe(), message.header().userId());
        if (owner.asynchronousCommands()) {
            owner.directCommand.deferTransferOut(message.header().userId(), command);
        } else {
            RuntimeAccountStateTransitions.transferOut(owner.runtimeState(), owner.identities(), message.header().userId(), command);
            completeTransferPublication();
        }
    }

    /**
     * 要求当前命令发布变化，实际完成与响应由外层提交流程决定。
     */
    private void completeTransferPublication() {
        owner.requestCommitPublication();
    }

    /**
     * 先核对收款用户及目标产品线，再以划转金额进入统一余额调整；不在这里重复执行转出账户扣款。
     * @param message 本次借用的原命令及载荷，调用期间保持缓冲有效
     * @param clusterTimestamp 原命令业务时间参数，本入口当前不直接读取
     */
    public void executeTransferIn(CoreMessage message, long clusterTimestamp) {
        owner.resultBuilder.setSingleChangedUser(message.header().userId());
        // 载荷解出的划转参数，后续沿用源/目标用户及产品线信息
        var command = TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe(), message.header().userId());
        if (message.header().userId() != command.targetUserId())
            throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer recipient mismatch");
        if (owner.runtimeState().productLine() != command.targetProductLine())
            throw new CoreStateRejectedException(
                    "PRODUCT_LINE_MISMATCH", "transfer target product line mismatch");
        adjustBalance(message.header().userId(), new BalanceAdjustmentCommand(
                command.asset(), command.amountUnits()));
    }

    /**
     * 解码划转身份，异步路径准备完成划转续步，冷路径完成原待转记录并要求发布。
     * @param message 本次借用的原命令及载荷，调用期间保持缓冲有效
     * @param clusterTimestamp 原命令业务时间参数，本入口当前不直接读取
     */
    public void executeCompleteTransfer(CoreMessage message, long clusterTimestamp) {
        // 本次需要完成的划转业务 ID
        long id = TradingCommandCodec.decodeCompleteTransfer(message.payloadUnsafe()).transferId();
        if (owner.asynchronousCommands()) {
            owner.directCommand.deferTransferComplete(message.header().userId(), id);
        } else {
            RuntimeAccountStateTransitions.completeTransfer(owner.runtimeState(), message.header().userId(), id);
            owner.requestCommitPublication();
        }
    }
}
