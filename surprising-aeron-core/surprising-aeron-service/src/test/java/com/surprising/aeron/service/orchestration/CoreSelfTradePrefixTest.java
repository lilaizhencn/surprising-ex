package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** Internal cancel-before-place intents must use the Matcher's current book, not stale Lane order presence. */
class CoreSelfTradePrefixTest {
    private static final long TIME = 1_700_000_000_000L, CAPITAL = 1_000_000;
    private long sequence;

    static Stream<Arguments> productsAndPriorFill() {
        return Stream.of(ProductLine.values()).flatMap(line -> Stream.of(0, 1, 2)
                .map(priorFill -> Arguments.of(line, priorFill)));
    }

    @ParameterizedTest @MethodSource("productsAndPriorFill")
    void cancelBeforePlaceHandlesRestingAndAlreadyMatchedOwnOrders(ProductLine line, int priorFill) throws Exception {
        var releaseMaker = new CountDownLatch(1);
        try (var core = new TradingCoreRuntime(line)) {
            var type = ContractType.valueOf(line.contractTypeCode());
            applied(core, message(core, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1", type.ordinal(),
                            "BTC", "USDT", type.isInverse() ? "BTC" : "USDT", 1, 1, type.isInverse() ? 1000 : 1,
                            100_000, 50_000, 0, 0, type.isDelivery() || type.isOption() ? TIME + 20_000 : 0,
                            type.isOption() ? 0 : -1, type.isOption() ? 100 : 0))));
            if (line != ProductLine.SPOT) applied(core, message(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                            ? new ApplyMarkPriceCommand("1", 100, 100, 100, 1, TIME)
                            : new ApplyMarkPriceCommand("1", 100, 1, TIME))));
            long maker = 7, candidate = 8;
            while (core.runtimeState.accountLaneId(candidate) == core.runtimeState.accountLaneId(maker)) candidate++;
            long taker = candidate;
            for (long user : new long[]{maker, taker}) for (String asset : new String[]{"BTC", "USDT"})
                applied(core, message(core, CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, CAPITAL))));
            applied(core, place(core, maker, 101, CoreOrderSide.SELL, 2));
            long handoff = core.runtimeState.laneHandoffEpoch();
            Map<Long, ResponseStatus> terminals = new HashMap<>();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                if (priorFill > 0) {
                    var entered = new CountDownLatch(1);
                    assertThat(core.runtimeState.dispatchAccountQuery(core.runtimeState.topology().accountLaneMask(maker), lane -> {
                        entered.countDown();
                        try {
                            if (!releaseMaker.await(5, TimeUnit.SECONDS)) throw new AssertionError("maker Lane gate timed out");
                        } catch (InterruptedException failure) { throw new AssertionError(failure); }
                    })).isTrue();
                    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                    var trade = place(core, taker, 102, CoreOrderSide.BUY, priorFill);
                    assertThat(submit(core, trade).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                    var tradeSlot = core.pendingMatching(core.matchingSequence(trade.header().commandId()));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!tradeSlot.settlementEvent().ready()) {
                        core.drainMatchingCompletions();
                        if (System.nanoTime() >= deadline) throw new AssertionError("Matcher did not publish the preceding trade");
                        Thread.onSpinWait();
                    }
                    // Native matching is complete, but the maker account cannot yet consume its fill.
                    assertThat(tradeSlot.settlementEvent().complete()).isFalse();
                }
                var next = place(core, maker, 103, CoreOrderSide.BUY, 1);
                assertThat(submit(core, next).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                long nextSequence = core.matchingSequence(next.header().commandId());
                // Admission is queued before releasing the maker; Lane's separate mailbox can overtake the old fill.
                releaseMaker.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (core.pendingMatchingCount() != 0) {
                    core.commits.commitReadyMatching(16, TIME, sequence, false,
                            (id, response) -> terminals.put(id, response.commandStatus()));
                    if (System.nanoTime() >= deadline) throw new AssertionError("self-trade prefix did not finish");
                    Thread.onSpinWait();
                }
                assertThat(terminals.get(nextSequence)).isEqualTo(ResponseStatus.APPLIED);
                assertThat(terminals.values()).allMatch(status -> status == ResponseStatus.APPLIED);
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            } finally { releaseMaker.countDown(); }
            Map<String, Long> totals = new HashMap<>();
            for (long user : new long[]{maker, taker}) {
                var state = CoreTestCompletion.userState(core, user);
                for (var balance : state.balances()) totals.merge(balance.asset(),
                        Math.addExact(balance.availableUnits(), balance.lockedUnits()), Math::addExact);
                assertThat(state.reservations()).hasSize(user == maker ? 1 : 0);
                long quantity = state.positions().stream().mapToLong(CorePositionView::signedQuantitySteps).sum();
                assertThat(quantity).isEqualTo(line == ProductLine.SPOT ? 0 : user == maker ? -priorFill : priorFill);
            }
            assertThat(totals).containsEntry("BTC", CAPITAL * 2).containsEntry("USDT", CAPITAL * 2);
            assertThat(CoreTestCompletion.orderState(core, maker, 101)).isNull();
            var missingCancel = message(core, CoreMessageType.CANCEL_ORDER, maker,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(999_999)));
            assertThat(CoreTestCompletion.applyAsynchronously(core, missingCancel).commandStatus())
                    .isEqualTo(ResponseStatus.REJECTED);
            assertThat(CoreTestCompletion.orderState(core, maker, 103).status()).isEqualTo("OPEN");
        } finally { releaseMaker.countDown(); }
    }

    private CoreResponse submit(TradingCoreRuntime core, CoreMessage message) {
        var window = new ClusterCommandWindow();
        boolean independent = core.prepareClusterPipelineScope(message, window);
        core.runtimeState.enterAsynchronousCommandScope();
        try {
            return core.applyDecodedCommand(message, TIME, message.header().sourceSequence(),
                    window.decodedIfPresent(message), independent);
        } finally {
            core.runtimeState.exitAsynchronousCommandScope();
            core.runtimeState.releaseOwnerLaneAccess();
        }
    }

    private void applied(TradingCoreRuntime core, CoreMessage message) {
        assertThat(CoreTestCompletion.applyAsynchronously(core, message).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
    }
    private CoreMessage place(TradingCoreRuntime core, long user, long id, CoreOrderSide side, long quantity) {
        return message(core, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(id, "1", side, 100, quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "prefix-" + id)));
    }
    private CoreMessage message(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        long next = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(424, next), core.productLine,
                CommandSource.GATEWAY, 424, next, user, TIME, next), payload);
    }
}
