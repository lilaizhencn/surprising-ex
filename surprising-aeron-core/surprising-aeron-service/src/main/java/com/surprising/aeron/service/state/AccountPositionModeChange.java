package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.UpdatePositionModeCommand;

/** 所属账户 Lane 检查所有币对敞口并切换持仓模式；Owner 仅推进最终修订号。 */
public final class AccountPositionModeChange implements java.util.function.IntFunction<Object> {
    /** 当前产品的控制窗口。 */
    private TradingRuntimeState runtime;
    /** 账户归属，不能按币对或持仓模式重新路由。 */
    private long userId;
    /** 唯一参与的账户 Lane。 */
    private int laneId;
    private UpdatePositionModeCommand command;
    /** 收集完成标志，重复轮询不重复修改。 */
    private boolean completed;

    public AccountPositionModeChange(TradingRuntimeState runtime, long userId, UpdatePositionModeCommand command) {
        if (runtime == null || userId <= 0 || command == null)
            throw new IllegalArgumentException("invalid account position mode change");
        reset(runtime, userId, command);
    }

    public static AccountPositionModeChange prepare(AccountPositionModeChange reuse,
            TradingRuntimeState runtime, long userId, UpdatePositionModeCommand command) {
        if (reuse == null) return new AccountPositionModeChange(runtime, userId, command);
        reuse.reset(runtime, userId, command);
        return reuse;
    }

    private void reset(TradingRuntimeState runtime, long userId, UpdatePositionModeCommand command) {
        if (runtime == null || userId <= 0 || command == null)
            throw new IllegalArgumentException("invalid account position mode change");
        runtime.assertOwner();
        this.runtime = runtime;
        this.userId = userId;
        this.command = command;
        this.laneId = runtime.topology().accountLaneId(userId);
        this.completed = false;
        runtime.dispatchControlLanes(1L << laneId, this);
    }

    @Override public Object apply(int ignoredLaneId) {
        return DerivativeAccountCommandProcessor.updateAccountPositionMode(runtime, userId, command);
    }

    public boolean poll() {
        runtime.assertOwner();
        if (completed) return true;
        if (!runtime.pollControlLanes()) return false;
        if (Boolean.TRUE.equals(runtime.controlLaneResult(laneId))) {
            runtime.markUserChanged(userId);
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        }
        completed = true;
        return true;
    }
}
