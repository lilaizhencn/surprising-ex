package com.surprising.aeron.service.execution;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncAccountBalanceCommandTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void transferRetainsSeparateProductAccountsAndRecoversExactlyOnce(ProductLine product) {
        ProductLine targetProduct = product == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
        try (var source = new TradingCoreRuntime(product); var target = new TradingCoreRuntime(targetProduct)) {
            var deposit = command(product, CoreMessageType.ADJUST_BALANCE, 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100)));
            assertThat(CoreTestCompletion.applyAsynchronously(source, deposit).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            var transfer = new TransferFundsCommand(7001, product, targetProduct, product.accountTypeCode(),
                    targetProduct.accountTypeCode(), "USDT", 30, "partition-transfer", "");
            byte[] payload = TradingCommandCodec.encodeTransferFunds(transfer);
            var out = command(product, CoreMessageType.TRANSFER_OUT, 2, payload);
            assertThat(CoreTestCompletion.applyAsynchronously(source, out).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(CoreTestCompletion.applyAsynchronously(source, out).status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(CoreTestCompletion.applyAsynchronously(source,
                    command(product, CoreMessageType.TRANSFER_OUT, 3, payload)).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(source.tradingState().user(11).totalUnits("USDT")).isEqualTo(70);
            try (var recovered = TradingCoreRuntime.fromSnapshot(product, source.snapshot(100))) {
                assertThat(recovered.pendingTransfers()).hasSize(1);
                var in = command(targetProduct, CoreMessageType.TRANSFER_IN, 1, payload);
                assertThat(CoreTestCompletion.applyAsynchronously(target, in).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(CoreTestCompletion.applyAsynchronously(target, in).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(CoreTestCompletion.applyAsynchronously(recovered,
                        command(product, CoreMessageType.COMPLETE_TRANSFER, 4,
                                TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(7001))))
                        .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(recovered.pendingTransfers()).isEmpty();
                assertThat(recovered.tradingState().user(11).totalUnits("USDT")
                        + target.tradingState().user(11).totalUnits("USDT")).isEqualTo(100);
                var tooMuch = new TransferFundsCommand(7002, product, targetProduct, product.accountTypeCode(),
                        targetProduct.accountTypeCode(), "USDT", 71, "insufficient-transfer", "");
                assertThat(CoreTestCompletion.applyAsynchronously(recovered,
                        command(product, CoreMessageType.TRANSFER_OUT, 5, TradingCommandCodec.encodeTransferFunds(tooMuch)))
                        .resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
                assertThat(recovered.tradingState().user(11).totalUnits("USDT")).isEqualTo(70);
                assertThat(recovered.pendingTransfers()).isEmpty();
            }
        }
    }

    private static CoreMessage command(ProductLine product, CoreMessageType type, long sequence, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(991, sequence), product,
                CommandSource.OPERATIONS, 991, sequence, 11, 1_700_000_000_000L + sequence, sequence), payload);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void accountAdjustmentAndCleanRejectionDoNotBorrowUnrelatedLanes(ProductLine product) throws Exception {
        try (var state = new TradingCoreRuntime(product); var serial = new TradingCoreRuntime(product)) {
            state.activate(); serial.activate();
            long user = 11;
            var field = state.runtimeState.getClass().getDeclaredField("laneWorkers");
            field.setAccessible(true);
            Object[] workers = (Object[]) field.get(state.runtimeState);
            int unrelated = (state.runtimeState.topology().accountLaneId(user) + 1) % workers.length;
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            Class<?> task = Class.forName("com.surprising.aeron.service.state.SettlementLaneWorker$Command");
            var submit = workers[unrelated].getClass().getDeclaredMethod("submit", task);
            submit.setAccessible(true);
            submit.invoke(workers[unrelated], Proxy.newProxyInstance(task.getClassLoader(), new Class<?>[]{task},
                    (proxy, method, args) -> {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane gate timeout");
                        return null;
                    }));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                long[] deltas = {100, -40, -61, 20, Long.MAX_VALUE};
                for (int i = 0; i < deltas.length; i++) {
                    long seq = i + 1;
                    var command = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                            new UUID(817, seq), product, CommandSource.OPERATIONS, 817, seq, user,
                            1_700_000_000_000L + seq, seq),
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", deltas[i])));
                    var actual = CoreTestCompletion.applyAsynchronously(state, command);
                    var expected = CoreTestCompletion.applyAsynchronously(serial, command);
                    assertThat(actual.commandStatus()).isEqualTo(expected.commandStatus())
                            .isEqualTo(i == 2 || i == 4 ? ResponseStatus.REJECTED : ResponseStatus.APPLIED);
                    assertThat(actual.resultCode()).isEqualTo(expected.resultCode());
                    assertThat(release.getCount()).as("unrelated account partition stays independent").isOne();
                    var access = state.runtimeState.getClass().getDeclaredField("ownerLaneAccess");
                    access.setAccessible(true);
                    assertThat(access.getBoolean(state.runtimeState)).isFalse();
                }
                for (int i = 0; i < 3; i++) {
                    var mode = command(product, CoreMessageType.UPDATE_POSITION_MODE, 100 + i,
                            TradingCommandCodec.encodeUpdatePositionMode(new UpdatePositionModeCommand(
                                    i < 2 ? CorePositionMode.HEDGE : CorePositionMode.ONE_WAY)));
                    var actual = CoreTestCompletion.applyAsynchronously(state, mode);
                    var expected = CoreTestCompletion.applyAsynchronously(serial, mode);
                    assertThat(actual.commandStatus()).isEqualTo(expected.commandStatus())
                            .isEqualTo(product == ProductLine.SPOT ? ResponseStatus.REJECTED : ResponseStatus.APPLIED);
                    assertThat(release.getCount()).isOne();
                }
            } finally { release.countDown(); }
            assertThat(state.tradingState().user(user).balances().get("USDT").availableUnits()).isEqualTo(80);
            assertThat(state.tradingState().businessStateHash()).isEqualTo(serial.tradingState().businessStateHash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
            }
        }
    }
}
