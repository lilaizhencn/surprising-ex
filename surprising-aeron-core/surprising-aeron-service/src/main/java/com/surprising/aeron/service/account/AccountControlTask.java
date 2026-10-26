package com.surprising.aeron.service.account;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/** 命令槽复用的账户控制续步，处理余额、转出与完成划转、杠杆、持仓模式和逐仓保证金。准备阶段派发所属 Lane，apply 在 Lane 内改账户，poll 在 Owner 收齐结果后发布元数据；同一对象在完成与释放前不能再次 prepare 或 clear。 构造约定：创建空控制续步，不派发业务；使用前需调用与本命令匹配的准备入口。 */
@NoArgsConstructor
public final class AccountControlTask implements IntFunction<Object>, BooleanSupplier {
    /** 余额增减任务标记，对应可用余额调整。 */
    private static final byte KIND_BALANCE = 1;
    /** 划转源账户扣款并登记待完成划转的任务标记。 */
    private static final byte KIND_TRANSFER_OUT = 2;
    /** 移除源账户待完成划转记录的任务标记。 */
    private static final byte KIND_TRANSFER_COMPLETE = 3;
    /** 杠杆设置或显式全仓保证金重定价的任务标记。 */
    private static final byte KIND_LEVERAGE = 4;
    /** 持仓模式切换的任务标记。 */
    private static final byte KIND_POSITION_MODE = 5;
    /** 逐仓保证金增减的任务标记。 */
    private static final byte KIND_POSITION_MARGIN = 6;

    /** 当前准备的任务种类，零表示尚未准备。 */
    private byte kind;
    /** 本续步关联的交易运行时，Owner 与 Lane 通过既有控制派发交接。 */
    private TradingAccountRuntime runtime;
    /** 余额、划转或持仓任务的目标用户；杠杆任务使用 leverageKey 中的用户。 */
    private long userId;
    /** 余额或逐仓保证金调整预先算好的下一产品修订号。 */
    private long nextRevision;
    /** 本续步是否已由 Owner 收集并完成，或划转完成路径无需派发。 */
    private boolean completed;
    /**  本续步是否已记录需要发布的变化，沿用各分支规则，不表示所有情况都有新扣款。 返回本续步记录的发布变化标记；转出路径即使幂等未新建也会置 true，不把它等同于必然发生资金变化。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean stateChanged;

    // 余额任务输入。
    /** 余额或划转账户操作使用的资产整数编号。 */
    private int assetId;
    /** 余额任务要增加的有符号可用金额，负数表示扣减。 */
    private long deltaUnits;

    // 划转任务输入与账户完成回执。
    /** 当前转出命令，Lane 处理完且 Owner 收集后解除引用。 */
    private TransferFundsCommand transferCommand;
    /** Lane 的实际变化结果，供转出发布路由、杠杆和持仓模式发布使用；Owner 收齐回执后才读取。 */
    private boolean changedOnLane;
    /** 完成划转任务要移除的划转编号。 */
    private long transferId;
    /** Lane 是否确实移除了对应待完成记录，Owner 据此核对路由一致性。 */
    private boolean transferRemoved;

    // 杠杆任务输入。
    /** 用户、合约和保证金模式组成的杠杆业务键。 */
    private CoreLeverageKey leverageKey;
    /** 原始不可变杠杆命令，包含目标杠杆及全仓重定价开关。 */
    private UpdateLeverageCommand leverageCommand;
    /** 杠杆对应合约的稠密编号。 */
    private int symbolId;

    // 持仓模式任务输入。
    /** 当前持仓模式切换命令。 */
    private UpdatePositionModeCommand positionModeCommand;

    // 逐仓保证金任务输入。
    /** 逐仓保证金任务已解析的运行时持仓键。 */
    private long positionKey;
    /** 当前逐仓保证金增减命令。 */
    private AdjustPositionMarginCommand positionMarginCommand;

    /**
     * 检查输入，重置续步并解析资产，预计算下一产品修订号，再把余额调整交给所属账户 Lane。
     * @param runtime 要派发并收集账户变化的交易运行时
     * @param identities 将业务资产、合约和持仓身份转为运行时键的字典
     * @param userId 本次控制命令操作的用户编号
     * @param asset 要调整的资产名称
     * @param deltaUnits 有符号可用余额增减金额
     */
    public AccountControlTask prepareBalanceAdjustment(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                                      long userId, String asset, long deltaUnits) {
        if (runtime == null || identities == null || asset == null || asset.isBlank() || userId <= 0)
            throw new IllegalArgumentException("invalid account balance adjustment");
        runtime.assertOwner();
        clear();
        this.kind = KIND_BALANCE;
        this.runtime = runtime;
        this.userId = userId;
        this.assetId = identities.assetId(AssetBalance.normalizeAsset(asset));
        this.deltaUnits = deltaUnits;
        this.nextRevision = Math.incrementExact(runtime.revision());
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
        return this;
    }

    /**
     * 检查转出身份、已有路由与待完成容量后派发 Lane；账户再次核对幂等并扣减可用余额，Owner 在回执后发布新路由。
     * @param runtime 要派发并收集账户变化的交易运行时
     * @param identities 将业务资产、合约和持仓身份转为运行时键的字典
     * @param userId 本次控制命令操作的用户编号
     * @param command 跨产品划转命令，包含源目标身份、资产、金额及划转编号
     */
    public AccountControlTask prepareTransferOut(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                                long userId, TransferFundsCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0)
            throw new IllegalArgumentException("transfer inputs are required");
        runtime.assertOwner();
        validateTransferSource(runtime, userId, command);
        // Owner 已发布的转出路由用户，零表示此划转尚无路由。
        long routedUser = runtime.pendingTransferUser(command.transferId());
        if (routedUser != 0 && routedUser != userId)
            throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        if (routedUser == 0 && !runtime.hasPendingTransferCapacity())
            throw new CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL", "pending transfer runtime capacity is full");
        clear();
        this.kind = KIND_TRANSFER_OUT;
        this.runtime = runtime;
        this.userId = userId;
        this.transferCommand = command;
        this.assetId = identities.assetId(command.asset());
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
        return this;
    }

    /**
     * 核对待完成划转路由属于此用户；路由不存在则直接完成且不派发，不会把同编号其他用户的划转当作重试。
     * @param runtime 要派发并收集账户变化的交易运行时
     * @param userId 本次控制命令操作的用户编号
     * @param transferId 需要确认完成的源侧划转编号
     */
    public AccountControlTask prepareTransferCompletion(TradingAccountRuntime runtime, long userId, long transferId) {
        if (runtime == null || userId <= 0 || transferId <= 0)
            throw new IllegalArgumentException("invalid transfer completion");
        runtime.assertOwner();
        // 本次完成划转的已发布路由用户，零表示无需再派发账户清理。
        long routed = runtime.pendingTransferUser(transferId);
        if (routed != 0 && routed != userId)
            throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        clear();
        this.kind = KIND_TRANSFER_COMPLETE;
        this.runtime = runtime;
        this.userId = userId;
        this.transferId = transferId;
        this.completed = routed == 0;
        if (!this.completed) runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
        return this;
    }

    /**
     * 重置续步，通过业务校验解析杠杆键及合约，再派发所属 Lane 执行账户杠杆变更。
     * @param runtime 要派发并收集账户变化的交易运行时
     * @param identities 将业务资产、合约和持仓身份转为运行时键的字典
     * @param userId 本次控制命令操作的用户编号
     * @param command 杠杆变更命令，包含合约、保证金模式、杠杆及重定价开关
     */
    public AccountControlTask prepareLeverageChange(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                                   long userId, UpdateLeverageCommand command) {
        clear();
        this.kind = KIND_LEVERAGE;
        this.runtime = Objects.requireNonNull(runtime);
        this.leverageKey = DerivativeAccountCommandProcessor.leverageKey(runtime, identities, userId, command);
        this.leverageCommand = command;
        this.symbolId = identities.symbolId(leverageKey.instrumentId());
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
        return this;
    }

    /**
     * 检查输入并保存用户和目标持仓模式，再交所属 Lane 检查全账户敞口并执行。
     * @param runtime 要派发并收集账户变化的交易运行时
     * @param userId 本次控制命令操作的用户编号
     * @param command 目标持仓模式命令
     */
    public AccountControlTask preparePositionModeChange(TradingAccountRuntime runtime, long userId,
                                                       UpdatePositionModeCommand command) {
        if (runtime == null || userId <= 0 || command == null)
            throw new IllegalArgumentException("invalid account position mode change");
        runtime.assertOwner();
        clear();
        this.kind = KIND_POSITION_MODE;
        this.runtime = runtime;
        this.userId = userId;
        this.positionModeCommand = command;
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
        return this;
    }

    /**
     * 解析逐仓持仓键并预计算产品修订号，把保证金调整交给用户所属 Lane；资金与持仓检查在账户步骤完成。
     * @param runtime 要派发并收集账户变化的交易运行时
     * @param identities 将业务资产、合约和持仓身份转为运行时键的字典
     * @param userId 本次控制命令操作的用户编号
     * @param command 逐仓保证金调整命令，正金额补入、负金额取回
     */
    public AccountControlTask preparePositionMarginAdjustment(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                                             long userId, AdjustPositionMarginCommand command) {
        clear();
        this.kind = KIND_POSITION_MARGIN;
        this.runtime = Objects.requireNonNull(runtime);
        this.positionKey = DerivativeAccountCommandProcessor.positionMarginKey(runtime, identities, userId, command);
        this.userId = userId;
        this.positionMarginCommand = command;
        this.nextRevision = Math.incrementExact(runtime.revision());
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
        return this;
    }

    /**
     * 重置命令输入、结果标记与引用以便再次准备；不等待在途消费者，调用方必须确认旧任务已不再使用本对象。
     */
    public void clear() {
        kind = 0;
        runtime = null;
        userId = 0;
        nextRevision = 0;
        completed = false;
        stateChanged = false;
        assetId = 0;
        deltaUnits = 0;
        transferCommand = null;
        changedOnLane = false;
        transferId = 0;
        transferRemoved = false;
        leverageKey = null;
        leverageCommand = null;
        symbolId = 0;
        positionModeCommand = null;
        positionKey = 0;
        positionMarginCommand = null;
    }

    /**
     * 由已派发到的 Lane 执行当前种类的账户操作；余额和划转路径保存回执标记，杠杆和持仓路径也直接保存原始类型结果，邮箱只通知完成，未准备的种类直接失败。
     * @param ignoredLaneId 控制派发回调传入的 Lane 编号；目标和输入已在准备阶段保存，本实现不读取它
     */
    @Override public Object apply(int ignoredLaneId) {
        return switch (kind) {
            case KIND_BALANCE -> {
                adjustAccountBalance(runtime, userId, assetId, deltaUnits);
                yield null;
            }
            case KIND_TRANSFER_OUT -> {
                // Lane 幂等检查后新建的待完成划转，null 表示相同命令已登记。
                TransferFundsCommand transfer = prepareTransferOut(runtime, userId, transferCommand);
                if (transfer != null) {
                    runtime.requireRevisionCapacity();
                    debitTransferAccount(runtime, userId, assetId, transferCommand.amountUnits());
                    runtime.putPendingTransfer(transfer);
                    runtime.incrementCommandRevision();
                    changedOnLane = true;
                }
                yield null;
            }
            case KIND_TRANSFER_COMPLETE -> {
                transferRemoved = completeTransfer(runtime, userId, transferId);
                yield null;
            }
            case KIND_LEVERAGE -> {
                changedOnLane = DerivativeAccountCommandProcessor.updateAccountLeverage(
                        runtime, leverageKey, symbolId, leverageCommand.leveragePpm(), leverageCommand.repriceCrossMargin());
                yield null;
            }
            case KIND_POSITION_MODE -> {
                changedOnLane = DerivativeAccountCommandProcessor.updateAccountPositionMode(runtime, userId, positionModeCommand);
                yield null;
            }
            case KIND_POSITION_MARGIN -> {
                assetId = DerivativeAccountCommandProcessor.adjustAccountPositionMargin(runtime, userId, positionKey, positionMarginCommand);
                yield null;
            }
            default -> throw new IllegalStateException("unknown account control task kind: " + kind);
        };
    }

    /**
     * 在 Owner 轮询 Lane 完成；收齐后按种类发布变化、划转路由和产品修订号，并标记本续步完成，重复轮询不会重复发布。
     */
    public boolean poll() {
        runtime.assertOwner();
        if (completed) return true;
        if (!runtime.pollControlLanes()) return false;
        switch (kind) {
            case KIND_BALANCE -> {
                runtime.markBalanceChanged(userId);
                runtime.setMetadata(runtime.productLine(), nextRevision);
                stateChanged = true;
            }
            case KIND_TRANSFER_OUT -> {
                // 内联执行已在账户应用时登记路由；独立工作线程模式由 Owner 收到账户回执后发布。
                if (changedOnLane && runtime.accountLanesStarted())
                    runtime.registerPendingTransferRoute(transferCommand.transferId(), userId);
                transferCommand = null;
                stateChanged = true;
            }
            case KIND_TRANSFER_COMPLETE -> {
                if (!transferRemoved) throw new IllegalStateException("pending transfer route has no Lane identity");
                if (runtime.accountLanesStarted()) runtime.completePendingTransferRoute(transferId, userId);
                stateChanged = true;
            }
            case KIND_LEVERAGE -> {
                if (changedOnLane) {
                    runtime.recordLeverageChange(leverageKey, leverageCommand.leveragePpm());
                    runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
                    stateChanged = true;
                }
            }
            case KIND_POSITION_MODE -> {
                if (changedOnLane) {
                    runtime.markUserChanged(userId);
                    runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
                    stateChanged = true;
                }
            }
            case KIND_POSITION_MARGIN -> {
                // Lane 保存的结算资产编号仅在回执完成后由 Owner 用于发布。
                runtime.recordUserSettlementChanges(userId, assetId, positionKey);
                runtime.setMetadata(runtime.productLine(), nextRevision);
                stateChanged = true;
            }
            default -> throw new IllegalStateException("unknown account control task kind: " + kind);
        }
        completed = true;
        return true;
    }

    /**
     * 把 BooleanSupplier 轮询接口接到 poll，供命令槽继续推进。
     */
    @Override public boolean getAsBoolean() {
        return poll();
    }

    /**
     * 检查可用余额精确加法和用户版本，金额不能为负；不存在的用户或资产按初始状态创建，已有冻结金额保持不变，产品修订交给外层。
     * @param runtime 本产品线的交易运行时
     * @param userId 本次余额或划转操作的账户用户编号
     * @param assetId 本产品账户所使用的资产整数编号
     * @param deltaUnits 有符号可用余额变化金额，负数表示扣减
     */
    private static void adjustAccountBalance(TradingAccountRuntime runtime, long userId, int assetId, long deltaUnits) {
        BalanceRuntime current = runtime.balance(userId, assetId);
        long currentAvailable = current == null ? 0 : current.availableUnits();
        long nextAvailable = Math.addExact(currentAvailable, deltaUnits);
        if (nextAvailable < 0) throw new IllegalArgumentException("available balance cannot be negative");
        UserRuntime user = runtime.user(userId);
        long userRevision = user == null ? 1 : Math.incrementExact(user.revision());
        var mode = user == null ? com.surprising.aeron.protocol.CorePositionMode.ONE_WAY : user.positionMode();
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
    private static TransferFundsCommand prepareTransferOut(TradingAccountRuntime runtime, long userId,
                                                           TransferFundsCommand command) {
        validateTransferSource(runtime, userId, command);
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
    private static void validateTransferSource(TradingAccountRuntime runtime, long userId, TransferFundsCommand command) {
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
    private static void debitTransferAccount(TradingAccountRuntime runtime, long userId, int assetId, long amountUnits) {
        BalanceRuntime balance = runtime.balance(userId, assetId);
        if (balance == null || balance.availableUnits() < amountUnits)
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "transfer available balance is insufficient");
        UserRuntime user = runtime.requireUser(userId);
        long userRevision = Math.incrementExact(user.revision());
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
