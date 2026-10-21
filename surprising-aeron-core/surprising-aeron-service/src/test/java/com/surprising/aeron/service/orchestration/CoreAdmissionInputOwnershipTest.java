package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.pipeline.CoreTestCompletion;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class CoreAdmissionInputOwnershipTest {
    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @ParameterizedTest @EnumSource(ProductLine.class)
    void marketOiDependencyFollowsProductRateAndCappedFloor(ProductLine product) {
        for (String mode : List.of("RATE_ZERO", "FLOOR_CAP", "FLOOR_ABOVE", "PROPORTIONAL")) {
            var value = CoreInstrument.from(product, instrument(product, mode));
            assertThat(value.usesOpenInterestForUserLimit()).isEqualTo(product.isDerivative() && mode.equals("PROPORTIONAL"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"RATE_ZERO", "FLOOR_CAP", "FLOOR_ABOVE", "PROPORTIONAL"})
    void ownerSkipsUnneededOiWithoutDroppingFixedFloorCapOrProportionalChecks(String mode) throws Exception {
        try (var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applied(core, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(instrument(core.productLine, mode)));
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME)));
            applied(core, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
            var totals = core.openInterestIndex.getClass().getDeclaredField("totals");
            totals.setAccessible(true);
            var guarded = new HashMap<String, Object>() {
                int reads;
                @Override public Object get(Object key) {
                    reads++;
                    if (!mode.equals("PROPORTIONAL")) throw new AssertionError("fixed user limit read market OI");
                    return super.get(key);
                }
            };
            totals.set(core.openInterestIndex, guarded);
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                var request = message(core, CoreMessageType.ORDER_PREFLIGHT_QUERY, 7, TradingCommandCodec.encodePlaceOrder(place(900, 1)));
                var response = core.apply(request);
                if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
                    long id = core.bookQueries.querySequence(request.header().commandId());
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while ((response = core.bookQueries.takeQueryResult(id)) == null) {
                        core.assertHealthy();
                        if (System.nanoTime() >= deadline) throw new AssertionError("preflight timeout");
                        Thread.onSpinWait();
                    }
                }
                assertThat(response.status()).isEqualTo(ResponseStatus.OK);
                applied(core, CoreMessageType.PLACE_ORDER, 7, TradingCommandCodec.encodePlaceOrder(place(101, 1)));
                applied(core, CoreMessageType.REPLACE_ORDER, 7,
                        TradingCommandCodec.encodeReplaceOrder(new ReplaceOrderCommand(101, place(102, 1))));
                applied(core, CoreMessageType.AMEND_ORDER, 7, TradingCommandCodec.encodeAmendOrder(
                        new AmendOrderCommand(102, 103, "client-103", 80L, 1L, CoreTimeInForce.GTC, false)));
                response = applied(core, CoreMessageType.PLACE_ORDER_BATCH, 7, TradingOrderBatchCodec.encodePlaceOrderBatch(
                        new PlaceOrderBatchCommand(List.of(place(201, 1), place(202, 1)))));
                var items = TradingOrderBatchCodec.decodeResult(response.data()).items();
                assertThat(items.getFirst().status()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(items.getLast().status()).isEqualTo(mode.equals("FLOOR_CAP") || mode.equals("FLOOR_ABOVE")
                        ? ResponseStatus.APPLIED : ResponseStatus.REJECTED);
                if (items.getLast().status() == ResponseStatus.REJECTED)
                    assertThat(items.getLast().resultCode()).isEqualTo(CoreResultCode.OPEN_INTEREST_LIMIT_EXCEEDED);
                applied(core, CoreMessageType.AMEND_ORDER_BATCH, 7, TradingOrderBatchCodec.encodeAmendOrderBatch(
                        new AmendOrderBatchCommand(List.of(new AmendOrderCommand(201, 301, "client-301", 80L, 1L, CoreTimeInForce.GTC, false)))));
                response = CoreTestCompletion.applyAsynchronously(core, message(core, CoreMessageType.PLACE_ORDER, 7,
                        TradingCommandCodec.encodePlaceOrder(place(999, 100))));
                assertThat(response.resultCode()).isEqualTo(CoreResultCode.POSITION_NOTIONAL_LIMIT_EXCEEDED);
                assertThat(guarded.reads > 0).isEqualTo(mode.equals("PROPORTIONAL"));
            }
            var user = CoreTestCompletion.userState(core, 7);
            assertThat(user.balances().getFirst().availableUnits() + user.balances().getFirst().lockedUnits()).isEqualTo(10_000);
            assertThat(user.positions()).isEmpty();
        }
    }

    @org.junit.jupiter.api.Test
    void fusedAdmissionYieldsUntilLedgerPublishesProportionalOi() throws Exception {
        String previous = System.setProperty("surprising.aeron.execution-mode", "FUSED");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applied(core, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(instrument(core.productLine, "PROPORTIONAL")));
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME)));
            applied(core, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
            try {
                assertThat(core.runtimeState.dispatchLedgerQuery(ignored -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Ledger gate timed out");
                    } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                })).isTrue();
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var command = message(core, CoreMessageType.PLACE_ORDER, 7, TradingCommandCodec.encodePlaceOrder(place(401, 1)));
                assertThat(core.apply(command).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                var pending = core.pendingMatching.required(core.matchingSequence(command.header().commandId()));
                assertThat(pending.isMatchingSubmitted()).isFalse();
                assertThat(pending.placeAdmission().accountReady()).isFalse();
                assertThat(pending.placeAdmission().complete()).isFalse();
                release.countDown();
                var terminal = new CoreResponse[1];
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (core.pendingMatching.firstSequence() != 0) {
                    core.commits.commitReadyMatching(64, TIME, command.header().sourceSequence(), false,
                            (sequence, response) -> terminal[0] = response);
                    if (System.nanoTime() >= deadline) throw new AssertionError("FUSED admission did not resume");
                }
                assertThat(terminal[0].commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(CoreTestCompletion.orderState(core, 7, 401).status()).isEqualTo("OPEN");
                var balance = CoreTestCompletion.userState(core, 7).balances().getFirst();
                assertThat(balance.availableUnits() + balance.lockedUnits()).isEqualTo(10_000);
                long hash = core.stateHash();
                try (var restored = TradingCoreRuntime.fromSnapshot(core.productLine, core.snapshot())) {
                    assertThat(restored.stateHash()).isEqualTo(hash);
                    assertThat(CoreTestCompletion.orderState(restored, 7, 401).status()).isEqualTo("OPEN");
                }
            } finally { release.countDown(); }
        } finally {
            if (previous == null) System.clearProperty("surprising.aeron.execution-mode");
            else System.setProperty("surprising.aeron.execution-mode", previous);
        }
    }

    private static RegisterInstrumentCommand instrument(ProductLine line, String mode) {
        var type = ContractType.valueOf(line.contractTypeCode());
        long floor = mode.equals("FLOOR_CAP") ? 1_000 : mode.equals("FLOOR_ABOVE") ? 2_000 : 200;
        return new RegisterInstrumentCommand("1", type.ordinal(), "BTC", "USDT", type.isInverse() ? "BTC" : "USDT",
                1, 1, type.isInverse() ? 1_000 : 1, 100_000, 50_000, 0, 0,
                type.isDelivery() || type.isOption() ? TIME + 20_000 : 0, type.isOption() ? 0 : -1, type.isOption() ? 100 : 0,
                10_000_000, 1_000, mode.equals("RATE_ZERO") ? 0 : 100_000, floor,
                List.of(new CoreRiskLimitBracket(1, 0, 1_000, 10_000_000, 100_000, 50_000)));
    }

    private static PlaceOrderCommand place(long id, long quantity) {
        return new PlaceOrderCommand(id, "1", CoreOrderSide.BUY, 80, quantity, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "client-" + id);
    }
    private CoreResponse applied(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        var response = CoreTestCompletion.applyAsynchronously(core, message(core, type, user, payload));
        assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
        return response;
    }
    private CoreMessage message(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        long next = ++sequence;
        var header = type == CoreMessageType.ORDER_PREFLIGHT_QUERY
                ? CoreMessageHeader.query(type, new UUID(510, next), core.productLine,
                        CommandSource.OPERATIONS, 510, next, user, TIME, next)
                : CoreMessageHeader.command(type, new UUID(510, next), core.productLine,
                        CommandSource.OPERATIONS, 510, next, user, TIME, next);
        return new CoreMessage(header, payload);
    }
}
