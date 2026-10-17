package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CoreRuntimeBalanceSurfaceTest {

    @Test
    void appliesBalanceCommandThroughAuthoritativeRuntimeAndMaterializesQueryState() {
        try (TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            CoreMessage command = new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), ProductLine.SPOT,
                    CommandSource.OPERATIONS, 9, 1, 1001, 1_000, 1),
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));

            assertThat(apply(state, command).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().user(1001).totalUnits("USDT")).isEqualTo(10_000);
            assertThat(state.tradingState().revision()).isEqualTo(1);
            assertThat(state.tradingState()).isSameAs(state.tradingState());
        }
    }

    @Test
    void handsProjectedRuntimeFromConstructionThreadToFirstCoreCommandThread() throws InterruptedException {
        try (TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            CoreMessage command = new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), ProductLine.SPOT,
                    CommandSource.OPERATIONS, 9, 1, 1001, 1_000, 1),
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ResponseStatus> status = new AtomicReference<>();
            AtomicReference<Long> balance = new AtomicReference<>();
            Thread coreThread = new Thread(() -> {
                try {
                    status.set(apply(state, command).status());
                    balance.set(state.tradingState().user(1001).totalUnits("USDT"));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    state.close();
                }
            });

            coreThread.start();
            coreThread.join();

            assertThat(failure.get()).isNull();
            assertThat(status.get()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(balance.get()).isEqualTo(10_000);
        }
    }

    private static CoreResponse apply(TradingCoreRuntime state, CoreMessage command) {
        CoreResponse response = state.apply(command);
        long directDeadline = System.nanoTime() + 5_000_000_000L;
        while (response == null && state.hasPendingDirectCommand() && System.nanoTime() < directDeadline) {
            response = state.pollDirectCommand();
            if (response == null) Thread.onSpinWait();
        }
        if (response == null && state.hasPendingDirectCommand()) {
            throw new AssertionError("Direct command timed out: " + command.header().messageType());
        }
        return response;
    }
}
