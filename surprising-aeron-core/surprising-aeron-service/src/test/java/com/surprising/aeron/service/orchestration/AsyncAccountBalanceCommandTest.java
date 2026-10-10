package com.surprising.aeron.service.orchestration;

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
    void crossUserCrossProductTransferSurvivesLostCreditAcknowledgement(ProductLine product) {
        ProductLine destination = product == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
        try (var source = new TradingCoreRuntime(product); var target = new TradingCoreRuntime(destination)) {
            CoreTestCompletion.applyAsynchronously(source, command(product, CoreMessageType.ADJUST_BALANCE, 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100))));
            byte[] payload = TradingCommandCodec.encodeTransferFunds(new TransferFundsCommand(8200,
                    product, destination, product.accountTypeCode(), destination.accountTypeCode(),
                    "USDT", 30, "cross-user-product", "", 11, 22));
            var out = command(product, CoreMessageType.TRANSFER_OUT, 2, payload);
            assertThat(CoreTestCompletion.applyAsynchronously(source, out).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            var in = new CoreMessage(CoreMessageHeader.command(CoreMessageType.TRANSFER_IN, new UUID(991, 3),
                    destination, CommandSource.GATEWAY, 991, 3, 22, 1_700_000_000_003L, 3), payload);
            assertThat(CoreTestCompletion.applyAsynchronously(target, in).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            try (var restoredSource = TradingCoreRuntime.fromSnapshot(product, source.snapshot(300));
                 var restoredTarget = TradingCoreRuntime.fromSnapshot(destination, target.snapshot(301))) {
                assertThat(CoreTestCompletion.applyAsynchronously(restoredSource, out).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(CoreTestCompletion.applyAsynchronously(restoredTarget, in).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(CoreTestCompletion.applyAsynchronously(restoredSource,
                        command(product, CoreMessageType.COMPLETE_TRANSFER, 4,
                                TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(8200))))
                        .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(restoredSource.pendingTransfers()).isEmpty();
                assertThat(restoredSource.tradingState().user(11).totalUnits("USDT")).isEqualTo(70);
                assertThat(restoredTarget.tradingState().user(22).totalUnits("USDT")).isEqualTo(30);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void transferRetainsSeparateProductAccountsAndRecoversExactlyOnce(ProductLine product) {
        ProductLine targetProduct = product == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
        try (var source = new TradingCoreRuntime(product); var target = new TradingCoreRuntime(targetProduct)) {
            var deposit = command(product, CoreMessageType.ADJUST_BALANCE, 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100)));
            assertThat(CoreTestCompletion.applyAsynchronously(source, deposit).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            var transfer = new TransferFundsCommand(7001, product, targetProduct, product.accountTypeCode(),
                    targetProduct.accountTypeCode(), "USDT", 30, "partition-transfer", "", 11L, 11L);
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
                // Target applied the credit but its acknowledgement was lost, then both sides restarted.
                try (var restoredTarget = TradingCoreRuntime.fromSnapshot(targetProduct, target.snapshot(101))) {
                    assertThat(CoreTestCompletion.applyAsynchronously(restoredTarget, in).status())
                            .isEqualTo(ResponseStatus.DUPLICATE);
                    assertThat(recovered.tradingState().user(11).totalUnits("USDT")
                            + restoredTarget.tradingState().user(11).totalUnits("USDT")).isEqualTo(100);
                    var changedTransfer = new TransferFundsCommand(7001, product, targetProduct,
                            product.accountTypeCode(), targetProduct.accountTypeCode(), "USDT", 31,
                            "partition-transfer", "", 11L, 11L);
                    var conflict = command(targetProduct, CoreMessageType.TRANSFER_IN, 1,
                            TradingCommandCodec.encodeTransferFunds(changedTransfer));
                    assertThat(CoreTestCompletion.applyAsynchronously(restoredTarget, conflict).resultCode())
                            .isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
                    assertThat(restoredTarget.tradingState().user(11).totalUnits("USDT")).isEqualTo(30);
                }
                assertThat(CoreTestCompletion.applyAsynchronously(recovered,
                        command(product, CoreMessageType.COMPLETE_TRANSFER, 4,
                                TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(7001))))
                        .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(recovered.pendingTransfers()).isEmpty();
                assertThat(recovered.tradingState().user(11).totalUnits("USDT")
                        + target.tradingState().user(11).totalUnits("USDT")).isEqualTo(100);
                var tooMuch = new TransferFundsCommand(7002, product, targetProduct, product.accountTypeCode(),
                        targetProduct.accountTypeCode(), "USDT", 71, "insufficient-transfer", "", 11L, 11L);
                assertThat(CoreTestCompletion.applyAsynchronously(recovered,
                        command(product, CoreMessageType.TRANSFER_OUT, 5, TradingCommandCodec.encodeTransferFunds(tooMuch)))
                        .resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
                assertThat(recovered.tradingState().user(11).totalUnits("USDT")).isEqualTo(70);
                assertThat(recovered.pendingTransfers()).isEmpty();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void differentUsersTransferWithinOneProductAndRecoverWithoutDoubleCredit(ProductLine product) {
        try (var state = new TradingCoreRuntime(product)) {
            assertThat(CoreTestCompletion.applyAsynchronously(state, command(product, CoreMessageType.ADJUST_BALANCE, 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100))))
                    .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            var transfer = new TransferFundsCommand(8100, product, product, product.accountTypeCode(),
                    product.accountTypeCode(), "USDT", 30, "user-transfer", "", 11, 22);
            byte[] payload = TradingCommandCodec.encodeTransferFunds(transfer);
            var out = command(product, CoreMessageType.TRANSFER_OUT, 2, payload);
            assertThat(CoreTestCompletion.applyAsynchronously(state, out).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot(200))) {
                var in = new CoreMessage(CoreMessageHeader.command(CoreMessageType.TRANSFER_IN, new UUID(991, 3),
                        product, CommandSource.GATEWAY, 991, 3, 22, 1_700_000_000_003L, 3), payload);
                assertThat(CoreTestCompletion.applyAsynchronously(restored, in).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                try (var recoveredAgain = TradingCoreRuntime.fromSnapshot(product, restored.snapshot(201))) {
                    assertThat(CoreTestCompletion.applyAsynchronously(recoveredAgain, in).status()).isEqualTo(ResponseStatus.DUPLICATE);
                    assertThat(CoreTestCompletion.applyAsynchronously(recoveredAgain, out).status()).isEqualTo(ResponseStatus.DUPLICATE);
                    var complete = command(product, CoreMessageType.COMPLETE_TRANSFER, 4,
                            TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(8100)));
                    assertThat(CoreTestCompletion.applyAsynchronously(recoveredAgain, complete).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                    assertThat(recoveredAgain.pendingTransfers()).isEmpty();
                    assertThat(recoveredAgain.tradingState().user(11).totalUnits("USDT")).isEqualTo(70);
                    assertThat(recoveredAgain.tradingState().user(22).totalUnits("USDT")).isEqualTo(30);
                    var wrongRecipient = command(product, CoreMessageType.TRANSFER_IN, 5, payload);
                    assertThat(CoreTestCompletion.applyAsynchronously(recoveredAgain, wrongRecipient).resultCode())
                            .isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
                    assertThat(recoveredAgain.tradingState().user(11).totalUnits("USDT")).isEqualTo(70);
                }
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
                var access = state.runtimeState.getClass().getDeclaredField("ownerLaneAccess");
                access.setAccessible(true);
                assertThat(access.getBoolean(state.runtimeState)).isFalse();
            }
            assertThat(state.tradingState().user(user).balances().get("USDT").availableUnits()).isEqualTo(80);
            assertThat(state.tradingState().businessStateHash()).isEqualTo(serial.tradingState().businessStateHash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
            }
        }
    }
}
