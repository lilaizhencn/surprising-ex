package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.service.state.model.CoreLeverageKey;

/** 所属账户 Lane 校验敞口并修改杠杆，Owner 收集变更并按日志顺序提交。 */
public final class AccountLeverageChange implements java.util.function.IntFunction<Object> {
    /** 当前产品控制窗口，仅允许一条控制命令在途。 */
    private TradingRuntimeState runtime;
    /** 账户、币对与保证金模式标识，不改变账户的资金分区。 */
    private CoreLeverageKey key;
    /** 目标杠杆，Lane 完成后用于发布不可变变更。 */
    private long leveragePpm;
    /** 账户唯一所属 Lane。 */
    private int laneId;
    private int symbolId;
    /** 防止重复轮询重复发布。 */
    private boolean completed;

    public AccountLeverageChange(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                  long userId, UpdateLeverageCommand command) {
        reset(runtime, identities, userId, command);
    }

    public static AccountLeverageChange prepare(AccountLeverageChange reuse,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId,
            UpdateLeverageCommand command) {
        if (reuse == null) return new AccountLeverageChange(runtime, identities, userId, command);
        reuse.reset(runtime, identities, userId, command);
        return reuse;
    }

    private void reset(TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId,
                       UpdateLeverageCommand command) {
        this.runtime = runtime;
        this.key = DerivativeAccountCommandProcessor.leverageKey(runtime, identities, userId, command);
        this.leveragePpm = command.leveragePpm();
        this.laneId = runtime.topology().accountLaneId(userId);
        this.symbolId = identities.symbolId(key.symbol());
        this.completed = false;
        runtime.dispatchControlLanes(1L << laneId, this);
    }

    @Override public Object apply(int ignoredLaneId) {
        return DerivativeAccountCommandProcessor.updateAccountLeverage(runtime, key, symbolId, leveragePpm);
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
