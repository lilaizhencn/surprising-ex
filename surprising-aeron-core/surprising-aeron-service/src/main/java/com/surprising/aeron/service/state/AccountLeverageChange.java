package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.service.state.model.CoreLeverageKey;

/** 所属账户 Lane 校验敞口并修改杠杆，Owner 收集变更并按日志顺序提交。 */
public final class AccountLeverageChange {
    /** 当前产品控制窗口，仅允许一条控制命令在途。 */
    private final TradingRuntimeState runtime;
    /** 账户、币对与保证金模式标识，不改变账户的资金分区。 */
    private final CoreLeverageKey key;
    /** 目标杠杆，Lane 完成后用于发布不可变变更。 */
    private final long leveragePpm;
    /** 账户唯一所属 Lane。 */
    private final int laneId;
    /** 防止重复轮询重复发布。 */
    private boolean completed;

    public AccountLeverageChange(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                  long userId, UpdateLeverageCommand command) {
        this.runtime = runtime;
        key = DerivativeAccountCommandProcessor.leverageKey(runtime, identities, userId, command);
        leveragePpm = command.leveragePpm();
        laneId = runtime.topology().accountLaneId(userId);
        int symbolId = identities.symbolId(key.symbol());
        runtime.dispatchControlLanes(1L << laneId,
                lane -> DerivativeAccountCommandProcessor.updateAccountLeverage(runtime, key, symbolId, leveragePpm));
    }

    public boolean poll() {
        runtime.assertOwner();
        if (completed) return true;
        if (!runtime.pollControlLanes()) return false;
        if (Boolean.TRUE.equals(runtime.controlLaneResult(laneId))) {
            runtime.recordLeverageChange(key, leveragePpm);
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        }
        completed = true;
        return true;
    }
}
