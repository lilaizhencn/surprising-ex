package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CompleteTransferCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpsertFeePolicyCommand;
import com.surprising.aeron.service.orchestration.pipeline.CoreTestCompletion;
import com.surprising.aeron.service.state.account.TransferRuntime;
import com.surprising.aeron.service.state.model.CoreFeePolicyState;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

/** The public cold business hash includes facts from every Lane and returns ownership afterwards. */
@org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
class CoreBusinessStateHashTest {
    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @ParameterizedTest @EnumSource(ProductLine.class)
    void hashesPoliciesAndTransfersInOneFenceAndResumesWorkers(ProductLine product) {
        try (var core = new TradingCoreRuntime(product)) {
            long first = 7, second = 8;
            while (core.runtimeState.topology().accountLaneId(first)
                    == core.runtimeState.topology().accountLaneId(second)) second++;
            apply(core, CoreMessageType.ADJUST_BALANCE, first,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100)));
            apply(core, CoreMessageType.ADJUST_BALANCE, second,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 200)));
            var policy = new UpsertFeePolicyCommand(91, 1, first, "", -10, 20, 1, true, TIME - 1, 0);
            apply(core, CoreMessageType.UPSERT_FEE_POLICY, first, TradingCommandCodec.encodeUpsertFeePolicy(policy));
            var a = transfer(product, 101, first, 30);
            var b = transfer(product, 102, second, 10);
            CoreMessage command = command(core, CoreMessageType.TRANSFER_OUT, first,
                    TradingCommandCodec.encodeTransferFunds(a));
            assertThat(CoreTestCompletion.applyAsynchronously(core, command).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            apply(core, CoreMessageType.TRANSFER_OUT, second, TradingCommandCodec.encodeTransferFunds(b));
            var policies = Map.of(91L, CoreFeePolicyState.from(policy));
            var transfers = Map.of(101L, new TransferRuntime(first, a), 102L, new TransferRuntime(second, b));
            // The canonical rule requires transfer-ID order, independently of input map iteration.
            var orderedTransfers = new java.util.TreeMap<>(transfers);
            long base = core.tradingState().businessStateHash();
            long expected = TradingCoreRuntime.canonicalBusinessStateHash(base, policies, orderedTransfers);
            assertThat(expected).isNotEqualTo(TradingCoreRuntime.canonicalBusinessStateHash(base, policies, Map.of()));
            assertThat(expected).isNotEqualTo(TradingCoreRuntime.canonicalBusinessStateHash(base, Map.of(), orderedTransfers));
            assertThat(core.runtimeState.ownerLaneAccess).isFalse();
            assertThatThrownBy(() -> core.runtimeState.pendingTransfersSnapshot()).isInstanceOf(IllegalStateException.class);
            assertThat(core.stateHash()).isEqualTo(expected);
            assertThat(core.runtimeState.ownerLaneAccess).isFalse();
            assertThatThrownBy(() -> core.runtimeState.pendingTransfersSnapshot()).isInstanceOf(IllegalStateException.class);
            assertThat(CoreTestCompletion.applyAsynchronously(core, command).status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(core.stateHash()).isEqualTo(expected);

            while (!core.runtimeState.tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            long epoch = core.runtimeState.laneHandoffEpoch();
            try {
                assertThat(core.stateHash()).isEqualTo(expected);
                assertThat(core.runtimeState.ownerLaneAccess).isTrue();
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(epoch);
            } finally { core.runtimeState.releaseOwnerLaneAccess(); }

            apply(core, CoreMessageType.COMPLETE_TRANSFER, first,
                    TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(101)));
            long completedBase = core.tradingState().businessStateHash();
            long completedHash = TradingCoreRuntime.canonicalBusinessStateHash(completedBase, policies,
                    Map.of(102L, new TransferRuntime(second, b)));
            assertThat(core.stateHash()).isEqualTo(completedHash).isNotEqualTo(expected);
            // A business write after the read proves that the query returned ownership to workers.
            apply(core, CoreMessageType.ADJUST_BALANCE, first,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1)));
            assertThat(core.stateHash()).isNotEqualTo(completedHash);
            assertThat(core.runtimeState.ownerLaneAccess).isFalse();
            core.assertClusterCallbackComplete();
        }
    }

    private TransferFundsCommand transfer(ProductLine source, long id, long user, long amount) {
        ProductLine target = source == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
        return new TransferFundsCommand(id, source, target, source.accountTypeCode(), target.accountTypeCode(),
                "USDT", amount, "hash-" + id, "核对", user, 22);
    }

    private void apply(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        assertThat(CoreTestCompletion.applyAsynchronously(core, command(core, type, user, payload)).commandStatus())
                .isEqualTo(ResponseStatus.APPLIED);
    }

    private CoreMessage command(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        long id = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(954, id), core.productLine,
                CommandSource.OPERATIONS, 954, id, user, TIME, id), payload);
    }
}
