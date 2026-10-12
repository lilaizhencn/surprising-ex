package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class CorePreflightLaneQueryTest {
    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void sixProductPreflightsMatchExistingRulesWithoutReservingFundsOrAllocatingIdentities(ProductLine line) throws Exception {
        try (var core = prepared(line)) {
            var before = core.tradingState();
            long positionCheckpoint = core.identities.positionCheckpoint();
            long handoff = core.runtimeState.laneHandoffEpoch();
            var identities = new RuntimeIdentityRegistry();
            try (var cold = RuntimeStateProjector.project(before, identities)) {
                for (var side : CoreOrderSide.values()) {
                    var command = place(900, side, 95, 3, false, "preflight-900");
                    var resolved = CoreOrderDecisionResolver.resolve(cold, identities, 1, command, TIME);
                    long reserved = RuntimeOrderAdmission.requiredReservation(cold, identities, 1, resolved,
                            0, new ActiveOrderIndex(before));
                    try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                        var response = query(core, 1, command);
                        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
                        assertThat(response.data()).isEqualTo(CoreOrderPreflightCodec.encode(
                                new CoreOrderPreflightView(resolved.reservationAsset(), reserved)));
                    }
                }
            }
            assertThat(core.tradingState()).isEqualTo(before);
            assertThat(core.identities.positionCheckpoint()).isEqualTo(positionCheckpoint);
            assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void duplicateIdUsesOwnerIndexAndDuplicateClientUsesLaneWithoutCrossAccountReads() throws Exception {
        try (var core = prepared(ProductLine.LINEAR_PERPETUAL)) {
            command(core, CoreMessageType.PLACE_ORDER, 7,
                    TradingCommandCodec.encodePlaceOrder(place(201, CoreOrderSide.SELL, 120, 1, false, "owner-201")));
            command(core, CoreMessageType.PLACE_ORDER, 1,
                    TradingCommandCodec.encodePlaceOrder(place(202, CoreOrderSide.BUY, 80, 1, false, "client-202")));
            var before = core.tradingState();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(query(core, 1, place(201, CoreOrderSide.BUY, 95, 1, false, "other-owner")).resultCode())
                        .isEqualTo(CoreResultCode.DUPLICATE_ORDER_ID);
                assertThat(query(core, 1, place(203, CoreOrderSide.BUY, 95, 1, false, "client-202")).resultCode())
                        .isEqualTo(CoreResultCode.DUPLICATE_CLIENT_ORDER_ID);
                assertThat(query(core, 1, place(204, CoreOrderSide.SELL, 95, 1, true, "reduce-empty")).resultCode())
                        .isEqualTo(CoreResultCode.REDUCE_ONLY_REQUIRES_POSITION_STATE);
                assertThat(query(core, 1, place(206, CoreOrderSide.BUY, 95, Long.MAX_VALUE, false, "overflow")).resultCode())
                        .isEqualTo(CoreResultCode.ARITHMETIC_OVERFLOW);
                var bad = message(core, 1, new byte[]{1});
                assertThat(core.requiresOwnerLaneAccessForPreparation(bad)).isFalse();
                assertThat(core.apply(bad).resultCode()).isEqualTo(CoreResultCode.INVALID_COMMAND);
                assertThat(query(core, 1, place(205, CoreOrderSide.BUY, 95, 1, false, "valid")).status()).isEqualTo(ResponseStatus.OK);
            }
            assertThat(core.tradingState()).isEqualTo(before);
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void reduceOnlyCapacityUsesLanePendingOrdersAndRejectionDoesNotChangeFunds() throws Exception {
        try (var core = prepared(ProductLine.LINEAR_PERPETUAL)) {
            command(core, CoreMessageType.PLACE_ORDER, 7,
                    TradingCommandCodec.encodePlaceOrder(place(301, CoreOrderSide.SELL, 100, 10, false, "maker")));
            command(core, CoreMessageType.PLACE_ORDER, 1,
                    TradingCommandCodec.encodePlaceOrder(place(302, CoreOrderSide.BUY, 100, 10, false, "taker")));
            command(core, CoreMessageType.PLACE_ORDER, 1,
                    TradingCommandCodec.encodePlaceOrder(place(303, CoreOrderSide.SELL, 110, 8, true, "close-pending")));
            var before = core.tradingState();
            var identities = new RuntimeIdentityRegistry();
            try (var cold = RuntimeStateProjector.project(before, identities)) {
                var valid = place(305, CoreOrderSide.SELL, 110, 2, true, "close-valid");
                var resolved = CoreOrderDecisionResolver.resolve(cold, identities, 1, valid, TIME);
                long expected = RuntimeOrderAdmission.requiredReservation(cold, identities, 1, resolved,
                        core.openInterestIndex.openInterestSteps("1"), new ActiveOrderIndex(before));
                try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                    assertThat(query(core, 1, place(304, CoreOrderSide.SELL, 110, 3, true, "close-oversized")).resultCode())
                            .isEqualTo(CoreResultCode.REDUCE_ONLY_CAPACITY_EXCEEDED);
                    assertThat(query(core, 1, valid).data()).isEqualTo(CoreOrderPreflightCodec.encode(
                            new CoreOrderPreflightView("USDT", expected)));
                }
            }
            assertThat(core.tradingState()).isEqualTo(before);
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void blockedLaneUsesCapturedMarketConfigurationAndOwnerReturnsImmediately() throws Exception {
        var release = new CountDownLatch(1);
        try (var core = prepared(ProductLine.LINEAR_PERPETUAL)) {
            var intent = place(400, CoreOrderSide.BUY, 95, 3, false, "frozen-preflight");
            var ids = new RuntimeIdentityRegistry();
            byte[] expected;
            try (var cold = RuntimeStateProjector.project(core.tradingState(), ids)) {
                var order = CoreOrderDecisionResolver.resolve(cold, ids, 1, intent, TIME);
                expected = CoreOrderPreflightCodec.encode(new CoreOrderPreflightView(order.reservationAsset(),
                        RuntimeOrderAdmission.requiredReservation(cold, ids, 1, order, 0, new ActiveOrderIndex(core.tradingState()))));
            }
            var entered = new CountDownLatch(1);
            long mask = core.runtimeState.topology().accountLaneMask(1);
            assertThat(core.runtimeState.dispatchAccountQuery(mask, lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("blocked Lane timeout"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            long handoff = core.runtimeState.laneHandoffEpoch();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                var request = message(core, 1, TradingCommandCodec.encodePlaceOrder(intent));
                assertThat(core.apply(request).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                long id = core.querySequence(request.header().commandId());
                assertThat(core.takeQueryResult(id)).isNull();
                assertThat(release.getCount()).isOne();
                var instrument = core.runtimeState.instrument("1");
                instrument.updateConfiguration(CoreInstrument.from(core.productLine, instrument(core.productLine, 200_000)));
                core.runtimeState.putMarkPrice(new MarkPriceRuntime(core.identities.findSymbolId("1"), instrument, 120, 2, TIME));
                release.countDown();
                assertThat(await(core, request, id).data()).isEqualTo(expected);
                assertThat(query(core, 1, intent).data()).isNotEqualTo(expected);
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            }
            core.assertClusterCallbackComplete();
        } finally { release.countDown(); }
    }

    private TradingCoreRuntime prepared(ProductLine line) {
        var core = new TradingCoreRuntime(line);
        try {
            command(core, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(instrument(line, 100_000)));
            if (line != ProductLine.SPOT) command(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(line.isOptionProduct() ? new ApplyMarkPriceCommand("1", 100, 100, 100, 1, TIME)
                            : new ApplyMarkPriceCommand("1", 100, 1, TIME)));
            String asset = line == ProductLine.INVERSE_PERPETUAL || line == ProductLine.INVERSE_DELIVERY ? "BTC" : "USDT";
            for (long user : new long[]{1, 7}) command(core, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 10_000)));
            return core;
        } catch (Throwable failure) { core.close(); throw failure; }
    }

    private static RegisterInstrumentCommand instrument(ProductLine line, long margin) {
        var type = ContractType.valueOf(line.contractTypeCode());
        return new RegisterInstrumentCommand("1", type.ordinal(), "BTC", "USDT", type.isInverse() ? "BTC" : "USDT",
                1, 1, type.isInverse() ? 1000 : 1, margin, 50_000, 500, 1000,
                type.isDelivery() || type.isOption() ? TIME + 20_000 : 0, type.isOption() ? 0 : -1, type.isOption() ? 100 : 0);
    }

    private static PlaceOrderCommand place(long id, CoreOrderSide side, long price, long quantity, boolean reduce, String client) {
        return new PlaceOrderCommand(id, "1", side, price, quantity, reduce, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, client);
    }

    private void command(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        long next = ++sequence;
        var request = new CoreMessage(CoreMessageHeader.command(type, new UUID(980, next), core.productLine,
                CommandSource.OPERATIONS, 980, next, user, TIME, next), payload);
        assertThat(CoreTestCompletion.applyAsynchronously(core, request).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
    }

    private CoreResponse query(TradingCoreRuntime core, long user, PlaceOrderCommand intent) {
        var request = message(core, user, TradingCommandCodec.encodePlaceOrder(intent));
        assertThat(core.requiresOwnerLaneAccessForPreparation(request)).isFalse();
        var response = core.apply(request);
        return response.resultCode() == CoreResultCode.MATCHING_PENDING
                ? await(core, request, core.querySequence(request.header().commandId())) : response;
    }

    private CoreResponse await(TradingCoreRuntime core, CoreMessage request, long id) {
        assertThat(id).isNotZero();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        CoreResponse response;
        while ((response = core.takeQueryResult(id)) == null) {
            core.assertHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("preflight query timeout");
            Thread.onSpinWait();
        }
        assertThat(core.takeQueryResult(id)).isNull();
        assertThat(core.querySequence(request.header().commandId())).isZero();
        return response;
    }

    private CoreMessage message(TradingCoreRuntime core, long user, byte[] payload) {
        long next = ++sequence;
        return new CoreMessage(CoreMessageHeader.query(CoreMessageType.ORDER_PREFLIGHT_QUERY, new UUID(981, next), core.productLine,
                CommandSource.OPERATIONS, 981, next, user, TIME, next), payload);
    }
}
