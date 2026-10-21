package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.pipeline.CoreTestCompletion;
import com.surprising.aeron.service.orchestration.retention.TerminalStateRetention;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.account.CoreUserState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.index.AdlPositionIndex;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.ledger.CoreTreasuryState;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.model.*;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.query.RuntimeOperationalQueryService;
import com.surprising.aeron.service.state.query.RuntimeRiskQueryService;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class CoreRiskLaneQueryTest {
    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL", "OPTION"})
    void ledgerEncodesOpenInterestAndBoundedMaintenanceUsers(ProductLine line) throws Exception {
        try (var core = initial(positionedState(line))) {
            core.activate();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(query(core, CoreMessageType.OPEN_INTEREST_QUERY, 0, new byte[0]).data())
                        .isEqualTo(CoreOpenInterestCodec.encode(List.of(new CoreOpenInterestView("1", 25, 0),
                                new CoreOpenInterestView("2", 0, 8), new CoreOpenInterestView("3", 10, 0))));
                var first = query(core, CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY, 0,
                        CoreMaintenanceCodec.encodeQuery(new CoreMaintenanceCodec.Query("1", 0, 1)));
                var page = CoreMaintenanceCodec.decodePage(first.data());
                assertThat(page.userIds()).containsExactly(1L);
                assertThat(page.hasMore()).isTrue();
                var next = CoreMaintenanceCodec.decodePage(query(core, CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY, 0,
                        CoreMaintenanceCodec.encodeQuery(new CoreMaintenanceCodec.Query("1", 1, 1))).data());
                assertThat(next.userIds()).containsExactly(7L);
                assertThat(next.hasMore()).isFalse();
            }
            core.assertClusterCallbackComplete();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL", "OPTION"})
    void currentRiskAndAdlReadActualLanesAndMatchColdCalculation(ProductLine line) throws Exception {
        var state = positionedState(line);
        var identities = new RuntimeIdentityRegistry();
        try (var expected = RuntimeStateProjector.project(state, identities); var core = initial(state)) {
            var globalRisk = RuntimeRiskQueryService.snapshots(expected, identities, 0);
            var userRisk = RuntimeRiskQueryService.snapshots(expected, identities, 1);
            var adlIndex = new AdlPositionIndex(state, identities);
            var adl = RuntimeRiskQueryService.adlCandidates(expected, identities, asset(line), adlIndex.positions(asset(line)), 10);
            core.activate();
            long handoff = core.runtimeState.laneHandoffEpoch();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(query(core, CoreMessageType.RISK_STATE_QUERY, 0, new byte[0]).data())
                        .isEqualTo(CoreRiskQueryCodec.encode(globalRisk));
                assertThat(query(core, CoreMessageType.RISK_STATE_QUERY, 1, new byte[0]).data())
                        .isEqualTo(CoreRiskQueryCodec.encode(userRisk));
                assertThat(query(core, CoreMessageType.RISK_STATE_QUERY, 99, new byte[0]).data())
                        .isEqualTo(CoreRiskQueryCodec.encode(List.of()));
                assertThat(query(core, CoreMessageType.ADL_CANDIDATE_QUERY, 0, CoreAdlQueryCodec.encodeQuery(asset(line), 10)).data())
                        .isEqualTo(CoreAdlQueryCodec.encodeCandidates(adl));
                assertThat(query(core, CoreMessageType.ADL_CANDIDATE_QUERY, 0, CoreAdlQueryCodec.encodeQuery(asset(line), 1)).data())
                        .isEqualTo(CoreAdlQueryCodec.encodeCandidates(adl.subList(0, 1)));
                assertThat(query(core, CoreMessageType.ADL_CANDIDATE_QUERY, 0, CoreAdlQueryCodec.encodeQuery("NONE", 10)).data())
                        .isEqualTo(CoreAdlQueryCodec.encodeCandidates(List.of()));
            }
            assertThat(globalRisk).hasSize(5);
            assertThat(adl).hasSize(2);
            assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            assertThat(core.tradingState()).isEqualTo(state);
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void isolatedOrderReservationIsDeductedOnItsLaneWithoutChangingFunds() throws Exception {
        try (var core = initial(positionedState(ProductLine.LINEAR_PERPETUAL))) {
            core.activate();
            var request = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, new UUID(970, 1), core.productLine,
                    CommandSource.OPERATIONS, 970, 1, 1, TIME, 1), TradingCommandCodec.encodePlaceOrder(
                    new PlaceOrderCommand(400, "3", CoreOrderSide.BUY, 77, 1, false, CoreMarginMode.ISOLATED,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "risk-query-isolated")));
            assertThat(CoreTestCompletion.applyAsynchronously(core, request).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            var before = core.tradingState();
            long reserved = before.users().get(1L).reservations().get(400L).remainingUnits();
            var identities = new RuntimeIdentityRegistry();
            try (var cold = RuntimeStateProjector.project(before, identities)) {
                var expected = RuntimeRiskQueryService.snapshots(cold, identities, 1);
                long handoff = core.runtimeState.laneHandoffEpoch();
                try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                    var response = query(core, CoreMessageType.RISK_STATE_QUERY, 1, new byte[0]);
                    assertThat(response.data()).isEqualTo(CoreRiskQueryCodec.encode(expected));
                    assertThat(CoreRiskQueryCodec.decode(response.data())).allSatisfy(row ->
                            assertThat(row.walletBalanceUnits()).isEqualTo(9500 - reserved));
                }
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
                assertThat(core.tradingState()).isEqualTo(before);
            }
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void queryFixesPriceAndConfigurationBeforeOwnerUpdatesThemAndDoesNotWaitForLane() throws Exception {
        var state = positionedState(ProductLine.LINEAR_PERPETUAL);
        var identities = new RuntimeIdentityRegistry();
        var release = new CountDownLatch(1);
        try (var cold = RuntimeStateProjector.project(state, identities); var core = initial(state)) {
            var expectedRisk = RuntimeRiskQueryService.snapshots(cold, identities, 0);
            var expectedAdl = RuntimeRiskQueryService.adlCandidates(cold, identities, "USDT",
                    new AdlPositionIndex(state, identities).positions("USDT"), 10);
            core.activate();
            long handoff = core.runtimeState.laneHandoffEpoch();
            var entered = new CountDownLatch(1);
            long mask = core.runtimeState.topology().accountLaneMask(1);
            assertThat(core.runtimeState.dispatchAccountQuery(mask, lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("blocked Lane timeout"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                var riskRequest = message(core, CoreMessageType.RISK_STATE_QUERY, 0, new byte[0]);
                var adlRequest = message(core, CoreMessageType.ADL_CANDIDATE_QUERY, 0, CoreAdlQueryCodec.encodeQuery("USDT", 10));
                assertThat(core.apply(riskRequest).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                assertThat(core.apply(adlRequest).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                long riskId = core.bookQueries.querySequence(riskRequest.header().commandId());
                long adlId = core.bookQueries.querySequence(adlRequest.header().commandId());
                assertThat(core.bookQueries.takeQueryResult(riskId)).isNull();
                assertThat(core.bookQueries.takeQueryResult(adlId)).isNull();
                // 修改Owner拥有的市场事实，不进入Lane或触碰账户。配置规范对象本身会原位更新。
                for (String name : state.instruments().keySet()) {
                    var instrument = core.runtimeState.instrument(name);
                    instrument.updateConfiguration(instrument(ProductLine.LINEAR_PERPETUAL, name, 60_000));
                    int symbol = core.identities.findSymbolId(name);
                    core.runtimeState.putMarkPrice(new MarkPriceRuntime(symbol, instrument, 120, 100, 100, 2, TIME));
                }
                assertThat(release.getCount()).isOne();
                release.countDown();
                assertThat(await(core, riskRequest, riskId).data()).isEqualTo(CoreRiskQueryCodec.encode(expectedRisk));
                assertThat(await(core, adlRequest, adlId).data()).isEqualTo(CoreAdlQueryCodec.encodeCandidates(expectedAdl));
                var fresh = CoreRiskQueryCodec.decode(query(core, CoreMessageType.RISK_STATE_QUERY, 0, new byte[0]).data());
                assertThat(fresh).allSatisfy(row -> {
                    assertThat(row.markPriceTicks()).isEqualTo(120);
                    assertThat(row.priceSequence()).isEqualTo(2);
                });
                assertThat(fresh).isNotEqualTo(expectedRisk);
                assertThat(fresh.stream().filter(row -> row.userId() == 1 && row.instrumentId().equals("1")).findFirst().orElseThrow()
                        .maintenanceMarginUnits()).isEqualTo(72);
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            }
            core.assertClusterCallbackComplete();
        } finally { release.countDown(); }
    }

    @Test
    void malformedAdlQueryRejectsOnlyThatRequestAndEmptyGlobalRiskIsImmediate() throws Exception {
        try (var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            core.activate();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(query(core, CoreMessageType.ADL_CANDIDATE_QUERY, 0, new byte[]{1}).resultCode())
                        .isEqualTo(CoreResultCode.INVALID_COMMAND);
                assertThat(query(core, CoreMessageType.RISK_STATE_QUERY, 0, new byte[0]).data())
                        .isEqualTo(CoreRiskQueryCodec.encode(List.of()));
                assertThat(query(core, CoreMessageType.ADL_CANDIDATE_QUERY, 0, CoreAdlQueryCodec.encodeQuery("USDT", 1)).data())
                        .isEqualTo(CoreAdlQueryCodec.encodeCandidates(List.of()));
            }
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void globalResponseLimitCountsAllLanesAndDoesNotPoisonFollowingQuery() throws Exception {
        var line = ProductLine.LINEAR_PERPETUAL;
        var instrument = instrument(line, "1", 50_000);
        var users = new LinkedHashMap<Long, CoreUserState>();
        for (long user = 1; user <= RuntimeOperationalQueryService.MAX_QUERY_ENTITIES + 1; user++)
            users.put(user, user(line, user, Map.of("1", position(line, "1", 1, CoreMarginMode.CROSS, 10))));
        var state = new TradingCoreState(line, 0, users, Map.of(), Map.of("1", instrument),
                new CoreRiskState(Map.of("1", new CoreMarkPriceState("1", 80, 1, TIME)), Map.of(), Map.of(), Map.of(), 1),
                CoreTreasuryState.empty());
        try (var core = initial(state)) {
            core.activate();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(query(core, CoreMessageType.RISK_STATE_QUERY, 0, new byte[0]).resultCode())
                        .isEqualTo(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
                var next = query(core, CoreMessageType.RISK_STATE_QUERY, 7, new byte[0]);
                assertThat(next.status()).isEqualTo(ResponseStatus.OK);
                assertThat(CoreRiskQueryCodec.decode(next.data())).singleElement().satisfies(row -> {
                    assertThat(row.userId()).isEqualTo(7);
                    assertThat(row.signedQuantitySteps()).isOne();
                    assertThat(row.walletBalanceUnits()).isEqualTo(10_000);
                    assertThat(row.unrealizedPnlUnits()).isEqualTo(-20);
                });
                core.assertHealthy();
            }
            core.assertClusterCallbackComplete();
        }
    }

    private static TradingCoreState positionedState(ProductLine line) {
        var instruments = new LinkedHashMap<String, CoreInstrument>();
        var prices = new LinkedHashMap<String, CoreMarkPriceState>();
        for (String name : List.of("1", "2", "3")) {
            instruments.put(name, instrument(line, name, 50_000));
            prices.put(name, new CoreMarkPriceState(name, 80, 100, 100, 1, TIME));
        }
        var first = user(line, 1, Map.of("1", position(line, "1", 10, CoreMarginMode.CROSS, 100),
                "2", position(line, "2", -5, CoreMarginMode.CROSS, 50),
                "3", position(line, "3", 10, CoreMarginMode.ISOLATED, 500)));
        var second = user(line, 7, Map.of("1", position(line, "1", 15, CoreMarginMode.CROSS, 200),
                "2", position(line, "2", -3, CoreMarginMode.ISOLATED, 60)));
        return new TradingCoreState(line, 0, Map.of(1L, first, 7L, second), Map.of(), instruments,
                new CoreRiskState(prices, Map.of(), Map.of(), Map.of(), 1), CoreTreasuryState.empty());
    }

    private static CoreInstrument instrument(ProductLine line, String name, long maintenance) {
        var type = ContractType.valueOf(line.contractTypeCode());
        return CoreInstrument.from(line, new RegisterInstrumentCommand(name, type.ordinal(), "BTC", "USDT", asset(line),
                1, 1, type.isInverse() ? 1000 : 1, 100_000, maintenance, 0, 0,
                type.isDelivery() || type.isOption() ? TIME + 20_000 : 0, type.isOption() ? 0 : -1, type.isOption() ? 100 : 0));
    }

    private static CorePositionState position(ProductLine line, String name, long quantity, CoreMarginMode mode, long margin) {
        return new CorePositionState(name, asset(line), mode, CorePositionSide.NET, quantity, 100,
                Math.abs(quantity) * 100, 0, margin);
    }

    private static CoreUserState user(ProductLine line, long id, Map<String, CorePositionState> positions) {
        long locked = positions.values().stream().mapToLong(CorePositionState::positionMarginUnits).sum();
        return new CoreUserState(line, id, 0, Map.of(asset(line), new AssetBalance(asset(line), 10_000 - locked, locked)),
                Map.of(), positions);
    }

    private static String asset(ProductLine line) { return line == ProductLine.INVERSE_PERPETUAL ? "BTC" : "USDT"; }

    private TradingCoreRuntime initial(TradingCoreState state) {
        return new TradingCoreRuntime(state.productLine(), 0, new LinkedHashMap<>(), new LinkedHashMap<>(), state,
                new TerminalStateRetention(), null, 0, Map.of(), Map.of(), null, null);
    }

    private CoreResponse query(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        var request = message(core, type, user, payload);
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
            if (System.nanoTime() >= deadline) throw new AssertionError("risk query timeout");
            Thread.onSpinWait();
        }
        assertThat(core.bookQueries.takeQueryResult(id)).isNull();
        assertThat(core.bookQueries.querySequence(request.header().commandId())).isZero();
        return response;
    }

    private CoreMessage message(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        long next = ++sequence;
        return new CoreMessage(CoreMessageHeader.query(type, new UUID(971, next), core.productLine,
                CommandSource.OPERATIONS, 971, next, user, TIME, next), payload);
    }
}
