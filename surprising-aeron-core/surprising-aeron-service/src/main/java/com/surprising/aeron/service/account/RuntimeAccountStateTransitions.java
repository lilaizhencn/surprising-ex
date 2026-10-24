package com.surprising.aeron.service.account;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 账户余额变更与待完成跨产品划转的业务步骤。账户控制续步组织身份校验和产品修订，这里的账户步骤只改变所属用户；目标产品的入账与源产品完成确认分别由自己的命令执行。 构造约定：只提供余额和划转步骤，权威余额及待完成记录仍由运行时所属 Lane 保存。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeAccountStateTransitions {

    /**
     * 检查可用余额精确加法和用户版本，金额不能为负；不存在的用户或资产按初始状态创建，已有冻结金额保持不变，产品修订交给外层。
     * @param runtime 本产品线的交易运行时
     * @param userId 本次余额或划转操作的账户用户编号
     * @param assetId 本产品账户所使用的资产整数编号
     * @param deltaUnits 有符号可用余额变化金额，负数表示扣减
     */
    static void adjustAccountBalance(TradingAccountRuntime runtime, long userId, int assetId, long deltaUnits) {
        // 当前资产的权威余额，首次入账时可以不存在。
        BalanceRuntime current = runtime.balance(userId, assetId);
        // 当前可用余额，资产还没有记录时按零处理。
        long currentAvailable = current == null ? 0 : current.availableUnits();
        // 精确应用本次增减或转出之后的新可用余额。
        long nextAvailable = Math.addExact(currentAvailable, deltaUnits);
        if (nextAvailable < 0) throw new IllegalArgumentException("available balance cannot be negative");
        // 当前用户元数据，首次余额调整时允许不存在。
        UserRuntime user = runtime.user(userId);
        // 在第一次账户写入前完成本段金额及用户版本的算术，避免这些溢出造成半写状态。
        // 写入前精确计算的新用户版本，新用户从一开始。
        long userRevision = user == null ? 1 : Math.incrementExact(user.revision());
        // 已有用户的持仓模式，新用户默认单向模式。
        var mode = user == null ? com.surprising.aeron.protocol.CorePositionMode.ONE_WAY : user.positionMode();
        // 资产首次出现时准备好的余额单元，已有资产时为 null。
        BalanceRuntime initialBalance = current == null ? new BalanceRuntime(userId, assetId, nextAvailable, 0) : null;
        runtime.setUserState(userId, userRevision, mode);
        if (initialBalance != null) runtime.putBalance(initialBalance);
        else runtime.replaceBalance(userId, assetId, nextAvailable, current.lockedUnits());
    }

    /**
     * 核对源用户与产品，检查待完成记录完整命令一致性；幂等命中返回 null，未启动 Lane 的同步路径还检查容量，不读取余额。
     * @param runtime 本产品线的交易运行时
     * @param userId 本次余额或划转操作的账户用户编号
     * @param command 用于匹配已有待完成记录或新建记录的完整划转命令
     */
    static TransferFundsCommand prepareTransferOut(TradingAccountRuntime runtime, long userId,
                                              TransferFundsCommand command) {
        validateTransferSource(runtime, userId, command);
        // 同划转编号的已有待完成记录，用户及整个命令必须一致。
        TransferFundsCommand existing = runtime.pendingTransfer(command.transferId());
        if (existing != null) {
            if (!existing.equals(command))
                throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer identity contains different data");
            return null;
        }
        if (!runtime.accountLanesStarted() && !runtime.hasPendingTransferCapacity())
            throw new CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL", "pending transfer runtime capacity is full");
        return command;
    }

    /**
     * 校验运行时、正数用户、命令与调用边界，要求用户及产品线都匹配转出来源。
     * @param runtime 本产品线的交易运行时
     * @param userId 本次余额或划转操作的账户用户编号
     * @param command 包含源用户及源产品线的划转命令
     */
    static void validateTransferSource(TradingAccountRuntime runtime, long userId, TransferFundsCommand command) {
        if (runtime == null || command == null || userId <= 0)
            throw new IllegalArgumentException("invalid runtime transfer out");
        runtime.assertOwner();
        if (userId != command.sourceUserId()) throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer sender mismatch");
        if (runtime.productLine() != command.sourceProductLine())
            throw new CoreStateRejectedException("PRODUCT_LINE_MISMATCH", "transfer source product line mismatch");
    }

    /**
     * 只花费源账户可用余额，先校验余额和用户修订并计算新金额，再写用户及余额，冻结部分不用于转出。
     * @param runtime 本产品线的交易运行时
     * @param userId 本次余额或划转操作的账户用户编号
     * @param assetId 本产品账户所使用的资产整数编号
     * @param amountUnits 命令已经校验的正数转出金额，只从可用余额扣减
     */
    static void debitTransferAccount(TradingAccountRuntime runtime, long userId, int assetId, long amountUnits) {
        // 源账户此资产的权威余额，缺失或可用不足时拒绝转出。
        BalanceRuntime balance = runtime.balance(userId, assetId);
        if (balance == null || balance.availableUnits() < amountUnits)
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "transfer available balance is insufficient");
        // 当前用户元数据，首次余额调整时允许不存在。
        UserRuntime user = runtime.requireUser(userId);
        // 写入前精确计算的新用户版本，新用户从一开始。
        long userRevision = Math.incrementExact(user.revision());
        // 精确应用本次增减或转出之后的新可用余额。
        long nextAvailable = Math.subtractExact(balance.availableUnits(), amountUnits);
        runtime.setUserState(userId, userRevision, user.positionMode());
        runtime.replaceBalance(userId, assetId, nextAvailable, balance.lockedUnits());
    }

    /**
     * 删除属于本用户的待完成源侧划转，确实移除才增加命令修订；缺失时返回 false，不再次扣款。
     * @param runtime 本产品线的交易运行时
     * @param userId 本次余额或划转操作的账户用户编号
     * @param transferId 要确认完成的源侧待完成划转编号
     */
    public static boolean completeTransfer(TradingAccountRuntime runtime, long userId, long transferId) {
        if (runtime == null || userId <= 0 || transferId <= 0) {
            throw new IllegalArgumentException("invalid runtime transfer completion");
        }
        runtime.assertOwner();
        if (!runtime.removePendingTransfer(transferId, userId)) return false;
        runtime.incrementCommandRevision();
        return true;
    }
}
