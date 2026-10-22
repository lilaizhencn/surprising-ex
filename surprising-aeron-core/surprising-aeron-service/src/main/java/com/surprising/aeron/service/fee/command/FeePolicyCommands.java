package com.surprising.aeron.service.fee.command;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.core.TradingCoreRuntime;

/**
 * 费率政策配置入口，同时作为 Lane 0 任务和 Owner 轮询续步使用；一条待完成政策占用 pending，账户执行与最终发布分别进行。
 */
public final class FeePolicyCommands implements java.util.function.IntFunction<Object>, java.util.function.BooleanSupplier {
    /** 拥有原控制命令及提交边界的交易 Owner。 */
    private final TradingCoreRuntime owner;
    /** 当前唯一待应用政策，经控制邮箱交给 Lane 0，Owner 收集完成或明确失败后清除。 */
    private com.surprising.aeron.protocol.UpsertFeePolicyCommand pending;

    /**
     * 保存非空 Owner 引用，等待具体配置命令进入。
     * @param owner 唯一交易 Owner 入口，沿用当前命令与提交边界
     */
    public FeePolicyCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 解码政策；异步时先保存唯一 pending 并派发 Lane 0，派发失败清槽，成功后登记 Owner 控制续步；冷路径直接更新并要求发布。
     * @param message 本次借用的原命令及载荷，调用期间保持缓冲有效
     * @param clusterTimestamp 原命令业务时间参数，本入口当前不直接读取
     */
    public void executeUpsertFeePolicy(CoreMessage message, long clusterTimestamp) {
        // 本请求载荷解出的费用政策配置
        var command = TradingCommandCodec.decodeUpsertFeePolicy(message.payloadUnsafe());
        if (owner.asynchronousCommands()) {
            if (pending != null) throw new IllegalStateException("fee policy command is still pending");
            pending = command;
            try { owner.runtimeState().dispatchControlLanes(1L, this); }
            catch (/* 本次派发或完成收集的原异常，清 pending 后继续传播 */ RuntimeException | Error failure) { pending = null; throw failure; }
            owner.directCommand.deferControl(this);
        } else {
            owner.runtimeState().upsertFeePolicy(command);
            owner.requestCommitPublication();
        }
    }

    /**
     * 只允许配置 Lane 0 在 pending 存在时应用政策，完成值交由原控制派发器记录。
     * @param laneId 必须为零的配置账户 Lane 编号
     */
    @Override public Object apply(int laneId) {
        if (laneId != 0 || pending == null) throw new IllegalStateException("fee policy reached the wrong configuration Lane");
        owner.runtimeState().upsertFeePolicy(pending);
        return null;
    }

    /**
     * Owner 轮询账户控制完成，未齐保留 pending；收集失败清槽后传播异常，成功时清槽并要求发布，返回真结束本续步。
     */
    @Override public boolean getAsBoolean() {
        try {
            if (!owner.runtimeState().pollControlLanes()) return false;
        } catch (/* 本次派发或完成收集的原异常，清 pending 后继续传播 */ RuntimeException | Error failure) { pending = null; throw failure; }
        pending = null;
        owner.requestCommitPublication();
        return true;
    }
}
