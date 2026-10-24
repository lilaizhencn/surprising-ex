package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.settlement.lifecycle.RuntimeLifecycleSettlementContinuation;

/** 测试侧推进实际分页结算工作；金额仍由生产计算，不是独立公式对照。 */
public final class RuntimeLifecycleSettlementFixture {
    private RuntimeLifecycleSettlementFixture() {}

    public static CoreSettlementProgressView applyRuntime(SettleInstrumentCommand command,
            Iterable<Long> userIds, ActiveOrderIndex orders, TradingAccountRuntime runtime,
            RuntimeIdentityRegistry identities) {
        var work = RuntimeLifecycleSettlementContinuation.prepare(null, command, userIds, orders, runtime, identities);
        CoreTestCompletion.completeControlWork(work::poll);
        return work.result();
    }
}
