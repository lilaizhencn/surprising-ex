package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.*;
import com.surprising.aeron.service.state.query.RuntimeOperationalQueryService;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CoreAlgoLaneQueryTest {
    @Test void deadlinePrecedesTheClaimLeaseAndTerminalParentsDoNotHideActiveWork() {
        var index = new com.surprising.aeron.service.state.index.AlgoOrderIndex(TradingCoreState.empty(LINE));
        index.apply(999,algo(999,7,"1",TIME+30000,List.of()));
        assertThat(index.query(7,"",TIME+9999,1)).isEmpty();
        assertThat(index.query(7,"",TIME+10000,1)).containsExactly(999L);
        var terminal = new CoreAlgoOrderState(998,7,"ended","1",0,CoreOrderSide.BUY,100,100,10,
                1,10,CoreMarginMode.CROSS,CorePositionSide.NET,false,false,CoreTimeInForce.GTC,3,
                0,"",TIME,0,TIME+1,TIME,TIME+1,2,List.of());
        index.apply(998,terminal);
        assertThat(index.query(7,"",0,1)).containsExactly(999L);
        assertThat(index.value(998)).isNotNull();
    }
    private static final ProductLine LINE = ProductLine.LINEAR_PERPETUAL;
    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @Test
    void queriesIncludeRetainedChildrenAndKeepGlobalDueOrderAcrossLanes() throws Exception {
        var state = preparedState(false);
        var identities = new RuntimeIdentityRegistry();
        try (var cold = RuntimeStateProjector.project(state, identities); var core = initial(state)) {
            var expected = RuntimeOperationalQueryService.algoOrders(cold, List.of(502L, 501L, 503L));
            core.activate();
            assertThat(core.activeOrderIndex.activeOrderRoute(102)).isNull();
            assertThat(core.activeOrderIndex.activeOrderRoute(104)).isNull();
            long handoff = core.runtimeState.laneHandoffEpoch();
            assertThat(java.util.Arrays.stream(TradingRuntimeState.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                    .doesNotContain("publishedAlgoOrders");
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(query(core, 0, 0, "", 0, 10).data()).isEqualTo(CoreAlgoOrderCodec.encodeList(expected));
                assertThat(CoreAlgoOrderCodec.decodeList(query(core, 1, 0, "", 0, 10).data()))
                        .extracting(CoreAlgoOrderView::algoOrderId).containsExactly(501L, 503L);
                assertThat(CoreAlgoOrderCodec.decodeList(query(core, 0, 0, "2", 0, 10).data()))
                        .extracting(CoreAlgoOrderView::algoOrderId).containsExactly(503L);
                assertThat(CoreAlgoOrderCodec.decodeList(query(core, 0, 0, "", TIME + 700, 10).data()))
                        .extracting(CoreAlgoOrderView::algoOrderId).containsExactly(502L);
                assertThat(CoreAlgoOrderCodec.decodeList(query(core, 0, 0, "", 0, 1).data()))
                        .extracting(CoreAlgoOrderView::algoOrderId).containsExactly(502L);
                var one = CoreAlgoOrderCodec.decodeList(query(core, 0, 501, "", 0, 1).data()).getFirst();
                assertThat(one.executedQuantitySteps()).isEqualTo(7);
                assertThat(one.activeQuantitySteps()).isEqualTo(3);
                assertThat(one.activeChildOrderCount()).isOne();
                var partial = CoreAlgoOrderCodec.decodeList(query(core, 0, 502, "", 0, 1).data()).getFirst();
                assertThat(partial.executedQuantitySteps()).isEqualTo(4);
                assertThat(partial.activeQuantitySteps()).isEqualTo(6);
                assertThat(partial.activeChildOrderCount()).isOne();
            }
            assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            assertThat(core.tradingState()).isEqualTo(state);
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void missingAndMalformedQueriesKeepTheirProtocolAndDoNotPoisonNextQuery() throws Exception {
        try (var core = initial(preparedState(false))) {
            core.activate();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(CoreAlgoOrderCodec.decodeList(query(core, 0, 999, "", 0, 10).data())).isEmpty();
                assertThat(CoreAlgoOrderCodec.decodeList(query(core, 99, 0, "", 0, 10).data())).isEmpty();
                var bad = message(new byte[]{1});
                assertThat(core.requiresOwnerLaneAccessForPreparation(bad)).isFalse();
                assertThat(core.apply(bad).resultCode()).isEqualTo(CoreResultCode.INVALID_COMMAND);
                assertThat(query(core, 0, 503, "", 0, 10).status()).isEqualTo(ResponseStatus.OK);
            }
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void scanLimitIsGlobalAcrossSelectedParentsAndFollowingSingleParentStillWorks() throws Exception {
        try (var core = initial(preparedState(true))) {
            core.activate();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                var request = message(CoreAlgoOrderCodec.encodeQuery(0, 0, "", 0, 10));
                assertThat(core.requiresOwnerLaneAccessForPreparation(request)).isFalse();
                assertThat(CoreTestCompletion.querySynchronously(core, request).resultCode()).isEqualTo(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
                assertThat(core.bookQueries.querySequence(request.header().commandId())).isZero();
                var response = query(core, 0, 503, "", 0, 10);
                assertThat(response.status()).isEqualTo(ResponseStatus.OK);
                assertThat(CoreAlgoOrderCodec.decodeList(response.data())).singleElement().satisfies(view -> {
                    assertThat(view.algoOrderId()).isEqualTo(503);
                    assertThat(view.executedQuantitySteps()).isZero();
                    assertThat(view.activeQuantitySteps()).isZero();
                });
            }
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void ownerCanReturnWhileAnAlgoLaneIsBlockedAndConsumesPageExactlyOnce() throws Exception {
        var release = new CountDownLatch(1);
        try (var core = initial(preparedState(false))) {
            core.activate();
            var entered = new CountDownLatch(1);
            assertThat(core.runtimeState.dispatchAccountQuery(core.runtimeState.topology().accountLaneMask(1), lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("blocked Lane timeout"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                var request = message(CoreAlgoOrderCodec.encodeQuery(0, 0, "", 0, 10));
                assertThat(core.apply(request).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                long id = core.bookQueries.querySequence(request.header().commandId());
                assertThat(core.bookQueries.takeQueryResult(id)).isNull();
                assertThat(release.getCount()).isOne();
                release.countDown();
                assertThat(CoreAlgoOrderCodec.decodeList(await(core, request, id).data()))
                        .extracting(CoreAlgoOrderView::algoOrderId).containsExactly(502L, 501L, 503L);
            }
            core.assertClusterCallbackComplete();
        } finally { release.countDown(); }
    }

    private static TradingCoreState preparedState(boolean oversized) {
        var instrument1 = instrument("1");
        var orders = Map.of(102L, order(102, 1, 4, 4, 0, CoreOrderStatus.FILLED),
                103L, order(103, 1, 5, 2, 3, CoreOrderStatus.OPEN),
                104L, order(104, 1, 2, 1, 1, CoreOrderStatus.CANCELED),
                201L, order(201, 7, 10, 4, 6, CoreOrderStatus.OPEN));
        var first = new CoreUserState(LINE, 1, 0, Map.of("USDT", new AssetBalance("USDT", 990, 10)),
                Map.of(103L, OrderReservation.create(103, "1", ReservationKind.DERIVATIVE_MARGIN, "USDT", 10, 5)), Map.of());
        var second = new CoreUserState(LINE, 7, 0, Map.of("USDT", new AssetBalance("USDT", 940, 60)),
                Map.of(201L, OrderReservation.create(201, "1", ReservationKind.DERIVATIVE_MARGIN, "USDT", 60, 10)), Map.of());
        // 每父单9,000个扫描项均在单单上限内；两个Lane合起来超过全局16,384扫描项。
        var firstChildren = oversized ? Collections.nCopies(9000, 102L) : List.of(102L, 103L, 104L);
        var secondChildren = oversized ? Collections.nCopies(9000, 201L) : List.of(201L);
        var algos = Map.of(501L, algo(501, 1, "1", TIME + 1000, firstChildren),
                502L, algo(502, 7, "1", TIME + 500, secondChildren),
                503L, algo(503, 1, "2", TIME + 1000, List.of()));
        return new TradingCoreState(LINE, 0, Map.of(1L, first, 7L, second), orders,
                Map.of("1", instrument1, "2", instrument("2")), CoreRiskState.empty(), CoreTreasuryState.empty(), Map.of(), algos);
    }

    private static CoreInstrument instrument(String name) {
        return CoreInstrument.from(LINE, new RegisterInstrumentCommand(name, ContractType.LINEAR_PERPETUAL.ordinal(),
                "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
    }

    private static CoreOrderState order(long id, long user, long quantity, long executed, long remaining, CoreOrderStatus status) {
        return new CoreOrderState(id, LINE, user, "1", CoreOrderSide.BUY, 100, quantity, executed, remaining, false, status, 1);
    }

    private static CoreAlgoOrderState algo(long id, long user, String symbol, long due, List<Long> children) {
        return new CoreAlgoOrderState(id, user, "algo-query-" + id, symbol, 0, CoreOrderSide.BUY, 100, 100, 10,
                1, 10, CoreMarginMode.CROSS, CorePositionSide.NET, false, false, CoreTimeInForce.GTC, 1,
                0, "", TIME, due, 0, TIME, TIME, 1, children);
    }

    private TradingCoreRuntime initial(TradingCoreState state) {
        return new TradingCoreRuntime(LINE, 0, new LinkedHashMap<>(), new LinkedHashMap<>(), state,
                new TerminalStateRetention(), null, 0, Map.of(), Map.of(), null, null);
    }

    private CoreResponse query(TradingCoreRuntime core, long user, long algo, String symbol, long due, int limit) {
        var request = message(CoreAlgoOrderCodec.encodeQuery(user, algo, symbol, due, limit));
        assertThat(core.requiresOwnerLaneAccessForPreparation(request)).isFalse();
        var response = core.apply(request);
        return response.resultCode() == CoreResultCode.MATCHING_PENDING
                ? await(core, request, core.bookQueries.querySequence(request.header().commandId())) : response;
    }

    private CoreResponse await(TradingCoreRuntime core, CoreMessage request, long id) {
        assertThat(id).isNotZero();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        CoreResponse response;
        while ((response = core.bookQueries.takeQueryResult(id)) == null) {
            core.assertHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("algo query timeout");
            Thread.onSpinWait();
        }
        assertThat(core.bookQueries.takeQueryResult(id)).isNull();
        assertThat(core.bookQueries.querySequence(request.header().commandId())).isZero();
        return response;
    }

    private CoreMessage message(byte[] payload) {
        long next = ++sequence;
        return new CoreMessage(CoreMessageHeader.query(CoreMessageType.ALGO_ORDER_QUERY, new UUID(975, next), LINE,
                CommandSource.OPERATIONS, 975, next, 0, TIME, next), payload);
    }
}
