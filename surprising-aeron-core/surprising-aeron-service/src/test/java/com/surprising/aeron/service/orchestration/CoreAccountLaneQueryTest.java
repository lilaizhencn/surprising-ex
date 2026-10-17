package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.*;
import com.surprising.aeron.service.state.query.RuntimeStateQueryService;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CoreAccountLaneQueryTest {
    private static final ProductLine LINE = ProductLine.LINEAR_PERPETUAL;
    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @Test
    void fiveAccountQueriesReadAndEncodeOnLaneAndMatchColdViews() throws Exception {
        try (var core = prepared()) {
            var identities = new RuntimeIdentityRegistry();
            try (var expected = RuntimeStateProjector.project(core.tradingState(), identities)) {
                var user = RuntimeStateQueryService.userState(expected, expected.accountLaneForUser(1), identities, 1);
                var order = RuntimeStateQueryService.orderState(expected,
                        expected.accountLaneForUser(7), identities, 200);
                long handoff = core.runtimeState.laneHandoffEpoch();
                try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                    assertThat(query(core, CoreMessageType.USER_STATE_QUERY, 1, new byte[0]).data())
                            .isEqualTo(CoreStateQueryCodec.encodeUserState(user.view()));
                    assertThat(CoreStateQueryCodec.decodeStateHash(query(core, CoreMessageType.USER_STATE_HASH_QUERY,
                            1, new byte[0]).data())).isEqualTo(user.stateHash());
                    assertThat(query(core, CoreMessageType.ORDER_STATE_QUERY, 0,
                            TradingCommandCodec.encodeOrderStateQuery(200)).data())
                            .isEqualTo(CoreStateQueryCodec.encodeOrderState(order.view()));
                    assertThat(CoreStateQueryCodec.decodeStateHash(query(core, CoreMessageType.ORDER_STATE_HASH_QUERY,
                            0, TradingCommandCodec.encodeOrderStateQuery(200)).data())).isEqualTo(order.stateHash());
                    assertThat(query(core, CoreMessageType.CLIENT_ORDER_STATE_QUERY, 7,
                            CoreStateQueryCodec.encodeClientOrderStateQuery("account-q-200")).data())
                            .isEqualTo(CoreStateQueryCodec.encodeOrderState(order.view()));
                }
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
                core.assertClusterCallbackComplete();
            }
        }
    }

    @Test
    void openOrderPagesAreEncodedByTheirLanesAndKeepGlobalCursorOrder() throws Exception {
        try (var core = prepared(); var guard = new OwnerAccountReadGuard(core.runtimeState)) {
            long handoff = core.runtimeState.laneHandoffEpoch();
            var all = query(core, CoreMessageType.USER_OPEN_ORDERS_QUERY, 0,
                    CoreStateQueryCodec.encodeOpenOrdersQuery(new CoreOpenOrdersQuery("1", 0, 10)));
            assertThat(CoreStateQueryCodec.decodeOpenOrders(all.data()).orders())
                    .extracting(CoreOrderStateView::orderId).containsExactly(202L, 200L);
            var cursor = query(core, CoreMessageType.USER_OPEN_ORDERS_QUERY, 0,
                    CoreStateQueryCodec.encodeOpenOrdersQuery(new CoreOpenOrdersQuery("1", 202, 1)));
            assertThat(CoreStateQueryCodec.decodeOpenOrders(cursor.data()).orders())
                    .extracting(CoreOrderStateView::orderId).containsExactly(200L);
            var user = query(core, CoreMessageType.USER_OPEN_ORDERS_QUERY, 1,
                    CoreStateQueryCodec.encodeOpenOrdersQuery(new CoreOpenOrdersQuery("1", 0, 10)));
            assertThat(CoreStateQueryCodec.decodeOpenOrders(user.data()).orders())
                    .extracting(CoreOrderStateView::orderId).containsExactly(202L);
            var empty = query(core, CoreMessageType.USER_OPEN_ORDERS_QUERY, 99,
                    CoreStateQueryCodec.encodeOpenOrdersQuery(new CoreOpenOrdersQuery("1", 0, 10)));
            assertThat(CoreStateQueryCodec.decodeOpenOrders(empty.data()).orders()).isEmpty();
            assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void orderIdOnlyQueryFindsRetainedTerminalOrderOnItsActualLane() throws Exception {
        var instrument = CoreInstrument.from(LINE, new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        var order = new CoreOrderState(991, LINE, 7, "1", CoreOrderSide.SELL, 100, 1, 0, 1,
                true, CoreOrderStatus.CANCELED, 2);
        var state = new TradingCoreState(LINE, 0, Map.of(7L, CoreUserState.empty(LINE, 7)),
                Map.of(991L, order), Map.of("1", instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
        try (var core = initial(state)) {
            core.activate();
            assertThat(core.activeOrderIndex.activeOrderRoute(991)).isNull();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                var response = query(core, CoreMessageType.ORDER_STATE_QUERY, 0, TradingCommandCodec.encodeOrderStateQuery(991));
                var view = CoreStateQueryCodec.decodeOrderState(response.data());
                assertThat(view.orderId()).isEqualTo(991);
                assertThat(view.userId()).isEqualTo(7);
                assertThat(view.status()).isEqualTo(CoreOrderStatus.CANCELED.name());
            }
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void missingAndMalformedQueriesPreserveTheirCodesAndDoNotPoisonLane() throws Exception {
        try (var core = prepared(); var guard = new OwnerAccountReadGuard(core.runtimeState)) {
            assertThat(query(core, CoreMessageType.USER_STATE_QUERY, 99, new byte[0]).resultCode())
                    .isEqualTo(CoreResultCode.ENTITY_NOT_FOUND);
            assertThat(query(core, CoreMessageType.ORDER_STATE_QUERY, 0,
                    TradingCommandCodec.encodeOrderStateQuery(99)).resultCode()).isEqualTo(CoreResultCode.ENTITY_NOT_FOUND);
            assertThat(query(core, CoreMessageType.CLIENT_ORDER_STATE_QUERY, 1,
                    CoreStateQueryCodec.encodeClientOrderStateQuery("missing")).resultCode()).isEqualTo(CoreResultCode.ENTITY_NOT_FOUND);
            assertThat(CoreStateQueryCodec.decodeStateHash(query(core, CoreMessageType.USER_STATE_HASH_QUERY, 99, new byte[0]).data())).isZero();
            assertThat(CoreStateQueryCodec.decodeStateHash(query(core, CoreMessageType.ORDER_STATE_HASH_QUERY, 0,
                    TradingCommandCodec.encodeOrderStateQuery(99)).data())).isZero();
            assertThat(query(core, CoreMessageType.ORDER_STATE_QUERY, 0, new byte[]{1}).resultCode()).isEqualTo(CoreResultCode.INVALID_COMMAND);
            assertThat(query(core, CoreMessageType.USER_STATE_QUERY, 1, new byte[0]).status()).isEqualTo(ResponseStatus.OK);
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void oversizedUserQueryRejectsOnlyItsResult() throws Exception {
        var balances = new LinkedHashMap<String, AssetBalance>();
        for (int index = 0; index <= RuntimeStateQueryService.MAX_USER_QUERY_ENTITIES; index++)
            balances.put("ASSET" + index, new AssetBalance("ASSET" + index, 1, 0));
        var user = new CoreUserState(LINE, 1, 0, balances, Map.of(), Map.of());
        var state = new TradingCoreState(LINE, 0, Map.of(1L, user, 7L, CoreUserState.empty(LINE, 7)),
                Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        try (var core = initial(state)) {
            core.activate();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(query(core, CoreMessageType.USER_STATE_QUERY, 1, new byte[0]).resultCode())
                        .isEqualTo(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
                assertThat(query(core, CoreMessageType.USER_STATE_QUERY, 7, new byte[0]).status()).isEqualTo(ResponseStatus.OK);
            }
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void ownerReturnsWhileTargetLaneIsBlockedAndConsumesResultOnceLater() throws Exception {
        var release = new CountDownLatch(1);
        try (var core = prepared()) {
            var entered = new CountDownLatch(1);
            assertThat(core.runtimeState.dispatchAccountQuery(core.runtimeState.topology().accountLaneMask(1), lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("blocked Lane timeout"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                var request = message(CoreMessageType.USER_STATE_QUERY, 1, new byte[0]);
                var response = core.apply(request);
                assertThat(response.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                assertThat(release.getCount()).isOne();
                long id = core.bookQueries.querySequence(request.header().commandId());
                assertThat(core.bookQueries.takeQueryResult(id)).isNull();
                release.countDown();
                assertThat(await(core, request, id).status()).isEqualTo(ResponseStatus.OK);
            }
            core.assertClusterCallbackComplete();
        } finally { release.countDown(); }
    }

    @Test
    void readScopeDoesNotPrepareOrTouchUncollectedControlChanges() throws Exception {
        try (var core = prepared()) {
            var instrument = core.runtimeState.instrument("1");
            long key = core.identities.findPositionKeyValue(1, instrument, CorePositionSide.NET);
            int symbolId = core.identities.findSymbolId("1");
            long mask = core.runtimeState.topology().accountLaneMask(1);
            int laneId = Long.numberOfTrailingZeros(mask);
            var lanesField = TradingRuntimeState.class.getDeclaredField("accountLanes");
            lanesField.setAccessible(true);
            var lane = ((AccountLaneState[]) lanesField.get(core.runtimeState))[laneId];
            var positionsField = AccountLaneState.class.getDeclaredField("positions");
            positionsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var original = (org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<PositionRuntime>) positionsField.get(lane);
            var reading = new AtomicBoolean();
            var proofReads = new AtomicInteger();
            var guarded = new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<PositionRuntime>(original) {
                @Override public PositionRuntime get(long positionKey) {
                    if (reading.get()) proofReads.incrementAndGet();
                    return super.get(positionKey);
                }
            };
            positionsField.set(lane, guarded);
            try {
                assertThatThrownBy(() -> core.runtimeState.readAccountLane(lane, ignored -> Boolean.TRUE))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("owning Lane thread");
                core.runtimeState.dispatchControlLanes(mask, ignored -> {
                    core.runtimeState.putRiskSnapshot(key, new com.surprising.aeron.service.state.risk.RiskSnapshotRuntime(
                            1, symbolId, CorePositionSide.NET, 1, 100, 0, 1, 1, CoreRiskStatus.NORMAL));
                    core.runtimeState.removeRiskSnapshot(key);
                    return null;
                });
                var complete = new AtomicBoolean();
                assertThat(core.runtimeState.dispatchAccountQuery(mask, actualLane -> {
                    reading.set(true);
                    try {
                        core.runtimeState.readAccountLane(actualLane, ignored -> {
                            assertThatThrownBy(() -> core.runtimeState.dispatchAccountQuery(mask, unusedLane -> {}))
                                    .isInstanceOf(IllegalStateException.class).hasMessage("account query producer must be Owner");
                            return Boolean.TRUE;
                        });
                    }
                    finally { reading.set(false); }
                    complete.set(true);
                })).isTrue();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!complete.get()) {
                    core.assertHealthy();
                    if (System.nanoTime() >= deadline) throw new AssertionError("read scope timeout");
                    Thread.onSpinWait();
                }
                assertThat(proofReads).hasValue(0);
                assertThat(core.runtimeState.pollControlLanes()).isTrue();
                core.runtimeState.clearCommittedChanges(core.identities);
            } finally { positionsField.set(lane, original); }
        }
    }

    @Test
    void closeJoinsAccountReadersBeforeClearingUnconsumedResults() {
        var core = prepared();
        try {
            var request = message(CoreMessageType.USER_STATE_QUERY, 1, new byte[0]);
            assertThat(core.apply(request).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        } finally { core.close(); }
        assertThat(core.bookQueries.queryIds).isEmpty();
        assertThat(core.bookQueries.completedBookQueries).isEmpty();
    }

    private TradingCoreRuntime initial(TradingCoreState state) {
        return new TradingCoreRuntime(LINE, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(), state,
                new TerminalStateRetention(), null, 0, Map.of(), Map.of(), null, null);
    }

    private TradingCoreRuntime prepared() {
        var core = new TradingCoreRuntime(LINE);
        try {
            command(core, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(
                    new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                            1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0)));
            command(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME)));
            for (long user : new long[]{1, 7}) command(core, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
            place(core, 7, 200, CoreOrderSide.SELL, 100, 2);
            place(core, 1, 201, CoreOrderSide.BUY, 100, 1);
            place(core, 1, 202, CoreOrderSide.BUY, 99, 1);
            return core;
        } catch (Throwable failure) { core.close(); throw failure; }
    }

    private void place(TradingCoreRuntime core, long user, long id, CoreOrderSide side, long price, long quantity) {
        command(core, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(id,
                "1", side, price, quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "account-q-" + id)));
    }

    private void command(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        long next = ++sequence;
        var request = new CoreMessage(CoreMessageHeader.command(type, new UUID(956, next), LINE,
                CommandSource.OPERATIONS, 956, next, user, TIME, next), payload);
        assertThat(CoreTestCompletion.applyAsynchronously(core, request).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
    }

    private CoreResponse query(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        var request = message(type, user, payload);
        assertThat(core.requiresOwnerLaneAccessForPreparation(request)).isFalse();
        var response = core.apply(request);
        if (response.resultCode() != CoreResultCode.MATCHING_PENDING) return response;
        return await(core, request, core.bookQueries.querySequence(request.header().commandId()));
    }

    private CoreResponse await(TradingCoreRuntime core, CoreMessage request, long id) {
        assertThat(id).isNotZero();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        CoreResponse response;
        while ((response = core.bookQueries.takeQueryResult(id)) == null) {
            core.assertHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("account query timeout");
            Thread.onSpinWait();
        }
        assertThat(core.bookQueries.takeQueryResult(id)).isNull();
        assertThat(core.bookQueries.querySequence(request.header().commandId())).isZero();
        return response;
    }

    private CoreMessage message(CoreMessageType type, long user, byte[] payload) {
        long next = ++sequence;
        return new CoreMessage(CoreMessageHeader.query(type, new UUID(957, next), LINE,
                CommandSource.OPERATIONS, 957, next, user, TIME, next), payload);
    }
}
