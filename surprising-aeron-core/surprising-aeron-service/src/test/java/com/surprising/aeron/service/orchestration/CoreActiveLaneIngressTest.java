package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/** 普通apply入口也必须使用实际Lane；等待仅在功能测试边界。 */
class CoreActiveLaneIngressTest {
    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void ordinaryIngressReturnsBeforeBlockedLaneAndCompletesFundsAndTradesWithoutOwnerAccountReads(ProductLine line) throws Exception {
        var release = new CountDownLatch(1);
        try (var core = new TradingCoreRuntime(line)) {
            var type = ContractType.valueOf(line.contractTypeCode());
            applied(core, message(core, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1", type.ordinal(),
                            "BTC", "USDT", type.isInverse() ? "BTC" : "USDT", 1, 1, type.isInverse() ? 1000 : 1,
                            100_000, 50_000, 500, 1000, type.isDelivery() || type.isOption() ? TIME + 20_000 : 0,
                            type.isOption() ? 0 : -1, type.isOption() ? 100 : 0))));
            if (line != ProductLine.SPOT) applied(core, message(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(line.isOptionProduct()
                            ? new ApplyMarkPriceCommand("1", 100, 100, 100, 1, TIME)
                            : new ApplyMarkPriceCommand("1", 100, 1, TIME))));
            var fee = new UpsertFeePolicyCommand(51, 2, 1, "1", 300, 700, 0, true, TIME, 0);
            assertThatThrownBy(() -> core.runtimeState.upsertFeePolicy(fee))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("configuration Lane 0");
            applied(core, message(core, CoreMessageType.UPSERT_FEE_POLICY, 0,
                    TradingCommandCodec.encodeUpsertFeePolicy(fee)));
            long configuredRevision = core.runtimeState.revision();
            applied(core, message(core, CoreMessageType.UPSERT_FEE_POLICY, 0,
                    TradingCommandCodec.encodeUpsertFeePolicy(fee)));
            assertThat(core.runtimeState.revision()).isEqualTo(configuredRevision);
            var stale = message(core, CoreMessageType.UPSERT_FEE_POLICY, 0,
                    TradingCommandCodec.encodeUpsertFeePolicy(new UpsertFeePolicyCommand(
                            51, 1, 1, "1", 300, 700, 0, true, TIME, 0)));
            assertThat(complete(core, stale, core.apply(stale)).resultCode()).isEqualTo(CoreResultCode.STALE_FEE_POLICY_VERSION);
            assertThat(core.runtimeState.revision()).isEqualTo(configuredRevision);
            String asset = type.isInverse() ? "BTC" : "USDT";
            var entered = new CountDownLatch(1);
            assertThat(core.runtimeState.dispatchAccountQuery(core.runtimeState.topology().accountLaneMask(1), lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("blocked Lane timeout"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            long handoff = core.runtimeState.laneHandoffEpoch();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                // No enterAsynchronousCommandScope call: activation itself selects the worker path.
                var adjustment = message(core, CoreMessageType.ADJUST_BALANCE, 1,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 10_000)));
                assertThat(core.apply(adjustment)).isNull();
                assertThat(core.pollDirectCommand()).isNull();
                assertThat(core.hasPendingDirectCommand()).isTrue();
                assertThat(release.getCount()).isOne();
                release.countDown();
                assertThat(complete(core, adjustment, null).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                applied(core, message(core, CoreMessageType.ADJUST_BALANCE, 7,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 10_000))));
                if (line == ProductLine.SPOT) applied(core, message(core, CoreMessageType.ADJUST_BALANCE, 7,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 10))));
                applied(core, place(core, 7, 101, CoreOrderSide.SELL));
                applied(core, place(core, 1, 102, CoreOrderSide.BUY));
                var failed = message(core, CoreMessageType.ADJUST_BALANCE, 1,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, -100_000)));
                var rejection = complete(core, failed, core.apply(failed));
                assertThat(rejection.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            }
            var maker = CoreTestCompletion.userState(core, 7);
            var taker = CoreTestCompletion.userState(core, 1);
            assertThat(maker.reservations()).isEmpty();
            assertThat(taker.reservations()).isEmpty();
            assertThat(core.activeOrderIndex.activeOrderRoute(101)).isNull();
            assertThat(core.activeOrderIndex.activeOrderRoute(102)).isNull();
            if (line == ProductLine.SPOT) {
                assertThat(taker.balances()).anySatisfy(balance -> {
                    assertThat(balance.asset()).isEqualTo("BTC");
                    assertThat(balance.availableUnits()).isEqualTo(2);
                });
            } else {
                assertThat(taker.positions()).singleElement().satisfies(position -> assertThat(position.signedQuantitySteps()).isEqualTo(2));
                assertThat(maker.positions()).singleElement().satisfies(position -> assertThat(position.signedQuantitySteps()).isEqualTo(-2));
            }
            assertThat(core.runtimeState.systemLedgerStarted()).isTrue();
            core.assertClusterCallbackComplete();
        } finally { release.countDown(); }
    }

    private CoreMessage place(TradingCoreRuntime core, long user, long id, CoreOrderSide side) {
        return message(core, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(id, "1", side, 100, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "active-" + id)));
    }

    private void applied(TradingCoreRuntime core, CoreMessage message) {
        var response = complete(core, message, core.apply(message));
        assertThat(response.commandStatus()).as("%s: %s", message.header().messageType(), response.resultCode()).isEqualTo(ResponseStatus.APPLIED);
    }

    private CoreResponse complete(TradingCoreRuntime core, CoreMessage message, CoreResponse response) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (core.hasPendingDirectCommand()) {
            response = core.pollDirectCommand();
            if (System.nanoTime() >= deadline) throw new AssertionError("direct completion timeout");
            Thread.onSpinWait();
        }
        long requested = core.matchingSequence(message.header().commandId());
        CoreResponse[] terminal = {response};
        while (core.firstPendingMatchingSequence() != 0) {
            core.commits.commitReadyMatching(16, TIME, message.header().sourceSequence(), false,
                    (id, result) -> { if (id == requested) terminal[0] = result; });
            if (System.nanoTime() >= deadline) throw new AssertionError("matching completion timeout");
            Thread.onSpinWait();
        }
        assertThat(terminal[0]).isNotNull();
        return terminal[0];
    }

    private CoreMessage message(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        long seq = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(987, seq), core.productLine,
                CommandSource.OPERATIONS, 987, seq, user, TIME, seq), payload);
    }
}
