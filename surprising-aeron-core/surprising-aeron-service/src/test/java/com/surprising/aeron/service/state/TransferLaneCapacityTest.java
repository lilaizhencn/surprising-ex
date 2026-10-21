package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.state.account.AccountControlTask;
import com.surprising.aeron.service.state.account.RuntimeAccountStateTransitions;
import com.surprising.aeron.service.state.account.TransferRuntime;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TransferLaneCapacityTest {
    @Test void fullRoutingCapacityRejectsBeforeDebitButAllowsIdenticalRetryAndCompletion() {
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(TradingCoreState.empty(ProductLine.SPOT), identities)) {
            RuntimeAccountStateTransitions.adjustBalance(runtime, identities, 7, new BalanceAdjustmentCommand("USDT", 100));
            var first = command(1);
            // Build the bounded live state directly; this is not a snapshot/restore fixture.
            for (int id = 1; id <= TradingRuntimeState.MAX_PENDING_TRANSFERS; id++)
                runtime.putPendingTransfer(new TransferRuntime(7, id == 1 ? first : command(id)));
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            assertThat(runtime.hasPendingTransferCapacity()).isFalse();
            var next = command(TradingRuntimeState.MAX_PENDING_TRANSFERS + 1L);
            var task = new AccountControlTask();
            assertThatThrownBy(() -> task.prepareTransferOut(runtime, identities, 7, next))
                    .hasMessageContaining("capacity is full");
            task.prepareTransferOut(runtime, identities, 7, first);
            LedgerControlTestSupport.await(runtime, task::poll);
            assertThat(runtime.pendingTransferUser(1)).isEqualTo(7);
            var completion = new AccountControlTask().prepareTransferCompletion(runtime, 7, 1);
            LedgerControlTestSupport.await(runtime, completion);
            assertThat(runtime.hasPendingTransferCapacity()).isTrue();
            task.prepareTransferOut(runtime, identities, 7, next);
            LedgerControlTestSupport.await(runtime, task::poll);
            assertThat(runtime.hasPendingTransferCapacity()).isFalse();
            assertThat(runtime.pendingTransferUser(next.transferId())).isEqualTo(7);
            int asset = identities.assetId("USDT");
            assertThat(AccountLaneQueryTestSupport.read(runtime, 7, () -> runtime.balance(7, asset).availableUnits()))
                    .isEqualTo(99);
        }
    }

    private static TransferFundsCommand command(long id) {
        return new TransferFundsCommand(id, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                "SPOT", "USDT_PERPETUAL", "USDT", 1, "capacity", "", 7, 8);
    }
}
