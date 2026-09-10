package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.UpdatePositionModeCommand;

/** 所属账户 Lane 检查所有币对敞口并切换持仓模式；Owner 仅推进最终修订号。 */
public final class AccountPositionModeChange {
    /** 当前产品的控制窗口。 */
    private final TradingRuntimeState runtime;
    /** 账户归属，不能按币对或持仓模式重新路由。 */
    private final long userId;
    /** 唯一参与的账户 Lane。 */
    private final int laneId;
    /** 收集完成标志，重复轮询不重复修改。 */
    private boolean completed;

    public AccountPositionModeChange(TradingRuntimeState runtime, long userId, UpdatePositionModeCommand command) {
        if (runtime == null || userId <= 0 || command == null)
            throw new IllegalArgumentException("invalid account position mode change");
        runtime.assertOwner();
        this.runtime = runtime;
        this.userId = userId;
        laneId = runtime.topology().accountLaneId(userId);
        runtime.dispatchControlLanes(1L << laneId,
                lane -> DerivativeAccountCommandProcessor.updateAccountPositionMode(runtime, userId, command));
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
