package com.surprising.aeron.service.core;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.account.AccountControlTask;
import com.surprising.aeron.service.account.RuntimeAccountStateTransitions;
import com.surprising.aeron.service.account.TransferRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 待转容量边界测试，先冷填路由和账户记录，再启动账户线程；预填记录没有实际扣款，不把它当作快照恢复或完整历史资金流水。
 */
class TransferLaneCapacityTest {
    /**
     * 预填到上限后拒绝新身份但允许同身份重试；完成一项释放容量后新划转才扣1，最终余额99且容量重新用满。
     */
    @Test void fullRoutingCapacityRejectsBeforeDebitButAllowsIdenticalRetryAndCompletion() {
        // 本例冷投影与账户任务共用的身份表
        var identities = new RuntimeIdentityRegistry();
        try (/* 先冷准备再启动账户线程的原运行时 */ var runtime = RuntimeStateProjector.project(TradingCoreState.empty(ProductLine.SPOT), identities)) {
            RuntimeAccountStateTransitions.adjustBalance(runtime, identities, 7, new BalanceAdjustmentCommand("USDT", 100));
            // 预填第一项与后续相同重试共用的划转参数
            var first = command(1);
            // 直接冷填有限容量状态，再启动账户线程；本例没有快照恢复。
            for (/* 预填待完成记录的正值身份循环下标 */ int id = 1; id <= TradingAccountRuntime.MAX_PENDING_TRANSFERS; id++)
                runtime.putPendingTransfer(new TransferRuntime(7, id == 1 ? first : command(id)));
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            assertThat(runtime.hasPendingTransferCapacity()).isFalse();
            // 超过初始容量的下一个新划转身份
            var next = command(TradingAccountRuntime.MAX_PENDING_TRANSFERS + 1L);
            // 复用的账户转出控制任务
            var task = new AccountControlTask();
            assertThatThrownBy(() -> task.prepareTransferOut(runtime, identities, 7, next))
                    .hasMessageContaining("capacity is full");
            task.prepareTransferOut(runtime, identities, 7, first);
            LedgerControlTestSupport.await(runtime, task::poll);
            assertThat(runtime.pendingTransferUser(1)).isEqualTo(7);
            // 释放第一条待转记录的完成续步
            var completion = new AccountControlTask().prepareTransferCompletion(runtime, 7, 1);
            LedgerControlTestSupport.await(runtime, completion);
            assertThat(runtime.hasPendingTransferCapacity()).isTrue();
            task.prepareTransferOut(runtime, identities, 7, next);
            LedgerControlTestSupport.await(runtime, task::poll);
            assertThat(runtime.hasPendingTransferCapacity()).isFalse();
            assertThat(runtime.pendingTransferUser(next.transferId())).isEqualTo(7);
            // USDT在本例身份表中的内部资产号
            int asset = identities.assetId("USDT");
            assertThat(AccountLaneQueryTestSupport.read(runtime, 7, () -> runtime.balance(7, asset).availableUnits()))
                    .isEqualTo(99);
        }
    }

    /**
     * 生成用户7向另一产品用户8转出1 USDT的固定参数，只有业务ID变化。
     * @param id 本次待转记录的业务ID
     */
    private static TransferFundsCommand command(long id) {
        return new TransferFundsCommand(id, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                "SPOT", "USDT_PERPETUAL", "USDT", 1, "capacity", "", 7, 8);
    }
}
