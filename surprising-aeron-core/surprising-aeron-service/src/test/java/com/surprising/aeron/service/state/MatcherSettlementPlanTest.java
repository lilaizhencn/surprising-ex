package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.matching.MatcherEventFixtures;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatcherSettlementPlanTest {
    @Test
    void directEventCannotRecycleBeforeReservedNonTradingLanesConsumeTheirTickets() {
        var topology = new LaneTopology(LaneTopology.ROUTE_VERSION, 1, 0, 0, 4,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (var runtime = new TradingRuntimeState(topology)) {
            var identities = new RuntimeIdentityRegistry();
            int symbol = identities.symbolId("BTC-USDT");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.putInstrument(instrument());
            var taker = order(11, 21, symbol, CoreOrderSide.BUY, 3);
            runtime.putOrder(taker);
            var id = new java.util.UUID(1, 2);
            var event = runtime.prepareDirectMatcherSettlement(1, 15, taker, null, 1, id, 0,
                    identities, 1, 1, List.of(), null);
            assertThat(event.ready()).isFalse();
            assertThatThrownBy(event::clear).isInstanceOf(IllegalStateException.class);
            assertThat(event.direct()).isTrue();
            var result = new CoreMatchingResult(true, "SUCCESS", List.of(), 0, true,
                    new CoreMatchingResult.NativeCommand(1, 1, 2, 11, 1, 1, 1, 1, 0),
                    new CoreMatchingResult.MatcherPrefix(1, 2), null, List.of(), fill(1).marketData());
            event.publishDirectResult(result);
            int actual = topology.accountLaneId(21);
            event.execute(runtime.accountLanes[actual]);
            assertThat(event.requiredLaneMask()).isEqualTo(1L << actual);
            assertThat(event.complete()).isFalse();
            assertThatThrownBy(event::clear).isInstanceOf(IllegalStateException.class);
            assertThat(event.direct()).isTrue();
            for (int lane = 0; lane < 4; lane++) if (lane != actual) event.execute(runtime.accountLanes[lane]);
            assertThat(event.complete()).isTrue();
            assertThat(runtime.collectMatcherSettlement(event)).isNotNull();
            runtime.releaseMatcherSettlement(event);
            var reused = runtime.prepareDirectMatcherSettlement(2, 15, runtime.order(11), null, 1, id, 0,
                    identities, 2, 2, List.of(), null);
            assertThat(reused).isSameAs(event);
            assertThat(reused.ready()).isFalse();
            assertThat(reused.complete()).isFalse();
            var failure = new IllegalStateException("matcher failed before publication");
            reused.failDirect(failure);
            assertThat(reused.ready()).isTrue();
            assertThatThrownBy(() -> reused.execute(runtime.accountLanes[actual]))
                    .isInstanceOf(IllegalStateException.class).hasCause(failure);
        }
    }

    @Test
    void sharedMakerQuantityIsValidatedAcrossItemsBeforeAnyLaneApplies() {
        try (var runtime = new TradingRuntimeState()) {
            var identities = new RuntimeIdentityRegistry();
            int symbol = identities.symbolId("BTC-USDT");
            var instrument = instrument();
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.putInstrument(instrument);
            var maker = order(10, 20, symbol, CoreOrderSide.SELL, 5);
            var first = order(11, 21, symbol, CoreOrderSide.BUY, 3);
            var second = order(12, 22, symbol, CoreOrderSide.BUY, 3);
            runtime.putOrder(maker); runtime.putOrder(first); runtime.putOrder(second);
            var scratch = new MatcherSettlementPlan.BatchValidationScratch();
            assertThat(MatcherSettlementPlan.buildBatchItem(1, first, instrument, fill(3), runtime, identities, scratch, new MatcherSettlementPlan()).tradeCount()).isEqualTo(1);
            assertThatThrownBy(() -> MatcherSettlementPlan.buildBatchItem(1, second, instrument, fill(3), runtime, identities, scratch, new MatcherSettlementPlan()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds");
            assertThat(runtime.order(10).remainingQuantitySteps()).isEqualTo(5);
            scratch.clear();
            MatcherSettlementPlan.buildBatchItem(1, first, instrument, fill(3), runtime, identities, scratch, new MatcherSettlementPlan());
            assertThat(MatcherSettlementPlan.buildBatchItem(1, second, instrument, fill(2), runtime, identities, scratch, new MatcherSettlementPlan()).tradeCount()).isEqualTo(1);
            assertThatThrownBy(() -> MatcherSettlementPlan.buildBatchItem(1, first, instrument, fill(1), runtime, identities, scratch, new MatcherSettlementPlan()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
    @Test
    void reusedPlanAndScratchDoNotRetainPriorMakerOrTriggerState() {
        try (var runtime = new TradingRuntimeState()) {
            var identities = new RuntimeIdentityRegistry(); int symbol = identities.symbolId("BTC-USDT");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0); runtime.putInstrument(instrument());
            runtime.putOrder(order(10,20,symbol,CoreOrderSide.SELL,5));
            var taker = order(11,21,symbol,CoreOrderSide.BUY,3); runtime.putOrder(taker);
            var scratch = new MatcherSettlementPlan.BatchValidationScratch(); var slot = new MatcherSettlementPlan();
            assertThat(MatcherSettlementPlan.buildBatchItem(1,taker,instrument(),fill(3),runtime,identities,scratch,slot)).isSameAs(slot);
            slot.clearReferences(); scratch.clear();
            runtime.putOrder(order(10,20,symbol,CoreOrderSide.SELL,1));
            assertThatThrownBy(() -> MatcherSettlementPlan.buildBatchItem(1,taker,instrument(),fill(3),runtime,identities,scratch,slot))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds");
        }
    }
    @Test
    void pooledPlanSwitchesFromDeepMakerLaneIndexBackToOneFillWithoutStaleLinks() {
        var topology = new LaneTopology(LaneTopology.ROUTE_VERSION,1,0,0,4,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED,16,16,16);
        try (var runtime = new TradingRuntimeState(topology)) {
            var identities = new RuntimeIdentityRegistry(); int symbol = identities.symbolId("BTC-USDT");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL,0); runtime.putInstrument(instrument());
            long takerUser = 21;
            while (topology.accountLaneId(takerUser) == topology.accountLaneId(20)) takerUser++;
            runtime.putOrder(order(10,20,symbol,CoreOrderSide.SELL,20));
            var taker = order(11,takerUser,symbol,CoreOrderSide.BUY,20); runtime.putOrder(taker);
            var events = new java.util.ArrayList<exchange.core2.core.common.MatcherResult.MatcherEvent>();
            for (int i = 0; i < 9; i++) events.add(MatcherEventFixtures.trade(10,20,100,1,false,false));
            var deep = new CoreMatchingResult(true,"SUCCESS",List.of(),0,true,
                    new CoreMatchingResult.NativeCommand(0,0,0,0,0,0,0,0,-1),new CoreMatchingResult.MatcherPrefix(0,0),null,
                    events,new MatcherResult.MarketData(List.of(),List.of(),0,0)).withCoreSequence(1);
            var scratch = new MatcherSettlementPlan.BatchValidationScratch(); var slot = new MatcherSettlementPlan();
            MatcherSettlementPlan.buildBatchItem(1,taker,instrument(),deep,runtime,identities,scratch,slot);
            int makerLane = topology.accountLaneId(20), count = 0;
            for (int event = slot.firstMatcherEvent(makerLane); event >= 0; event = slot.nextMatcherEvent(event,makerLane)) count++;
            assertThat(count).isEqualTo(9);
            slot.clearReferences(); scratch.clear();
            MatcherSettlementPlan.buildBatchItem(1,taker,instrument(),fill(1),runtime,identities,scratch,slot);
            assertThat(slot.firstMatcherEvent(makerLane)).isZero();
            assertThat(slot.nextMatcherEvent(0,makerLane)).isEqualTo(-1);
            assertThat(slot.tradeCount()).isOne();
        }
    }
    @Test
    void smallerBatchReusesCapacityAndIgnoresStaleTailPlans() {
        try (var runtime = new TradingRuntimeState()) {
            var identities = new RuntimeIdentityRegistry();
            int symbol = identities.symbolId("BTC-USDT");
            var instrument = instrument();
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.putInstrument(instrument);
            var event = new MatcherSettlementEvent();
            var storage = event.batchStorage(20);
            long userId = 21;
            long laneMask = runtime.topology().accountLaneMask(userId);
            storage.plans[0] = MatcherSettlementPlan.empty(1, userId, new long[]{11}, runtime);
            // 尾部属于上代且序号不同；不能参与本代校验、执行或收集。
            storage.plans[1] = MatcherSettlementPlan.empty(99, userId, new long[]{12}, runtime);
            storage.instruments[0] = instrument;
            assertThat(event.batchStorage(1)).isSameAs(storage);
            event.prepareBatch(1, laneMask, 1, 1, storage.plans, 1, runtime, identities,
                    storage.instruments, storage.baseAssetIds, storage.quoteAssetIds,
                    storage.settleAssetIds, runtime.topology().accountLaneCount());
            assertThat(event.planCount()).isOne();
            assertThat(event.plan(0).coreSequence()).isEqualTo(1);
            assertThatThrownBy(() -> event.plan(1)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Test
    void singleCommandSlotReusesPlanWithoutLeakingPriorOrdersOrCancellations() {
        try (var runtime = new TradingRuntimeState()) {
            var identities = new RuntimeIdentityRegistry();
            int symbol = identities.symbolId("BTC-USDT");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.putInstrument(instrument());
            runtime.putOrder(order(10, 20, symbol, CoreOrderSide.SELL, 5));
            runtime.putOrder(order(11, 21, symbol, CoreOrderSide.BUY, 3));
            var slot = new MatcherSettlementPlan();
            assertThat(MatcherSettlementPlan.buildInto(slot, 1, 11, 21, 11, 0,
                    fill(3), runtime, identities)).isSameAs(slot);
            long[] cancellations = {12};
            assertThat(slot.preCancellations(cancellations)).isSameAs(slot);
            cancellations[0] = 999;
            assertThat(slot.preCancellationOrderId(0)).isEqualTo(12);
            slot.clearReferences();
            assertThat(slot.matcherEventCount()).isZero();
            assertThat(slot.preCancellationCount()).isZero();
            assertThat(MatcherSettlementPlan.emptyInto(slot, 2, 21, 20, 22, runtime)).isSameAs(slot);
            assertThat(slot.orderCount()).isEqualTo(2);
            assertThat(slot.orderId(0)).isEqualTo(20);
            assertThat(slot.orderId(1)).isEqualTo(22);
            assertThat(slot.tradeCount()).isZero();
            assertThat(slot.matcherEventCount()).isZero();
            assertThat(MatcherSettlementPlan.buildInto(slot, 3, 11, 21, 11, 0,
                    fill(1, 3), runtime, identities)).isSameAs(slot);
            assertThat(slot.orderCount()).isEqualTo(2);
            assertThat(slot.orderId(0)).isEqualTo(11);
            assertThat(slot.orderId(1)).isEqualTo(10);
            assertThat(slot.preCancellationCount()).isZero();
        }
    }

    @Test
    void expectedCancellationsAreWrittenInAdmissionOrderIntoReusablePlanStorage() {
        try (var runtime = new TradingRuntimeState()) {
            var identities = new RuntimeIdentityRegistry();
            int symbol = identities.symbolId("BTC-USDT");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.putInstrument(instrument());
            runtime.putOrder(order(11, 21, symbol, CoreOrderSide.BUY, 3));
            var slot = MatcherSettlementPlan.buildInto(new MatcherSettlementPlan(), 1, 11, 21, 11, 0,
                    new CoreMatchingResult(true, "SUCCESS", List.of(), 0, true,
                            new CoreMatchingResult.NativeCommand(1, 1, 2, 11, 1, 1, 1, 1, 0),
                            new CoreMatchingResult.MatcherPrefix(1, 2), null, List.of(),
                            new MatcherResult.MarketData(List.of(), List.of(), 0, 0)), runtime, identities);
            var cancellations = List.of(new com.surprising.aeron.service.matching.CoreCancellationResult(13, true, "CANCELLED"),
                    new com.surprising.aeron.service.matching.CoreCancellationResult(12, false, "NOT_FOUND"),
                    new com.surprising.aeron.service.matching.CoreCancellationResult(11, true, "CANCELLED"));
            slot.preCancellationsFromExpected(List.of(12L, 11L, 13L), cancellations);
            assertThat(slot.preCancellationCount()).isEqualTo(2);
            assertThat(slot.preCancellationOrderId(0)).isEqualTo(11);
            assertThat(slot.preCancellationOrderId(1)).isEqualTo(13);
        }
    }

    @Test
    void singleEventAndRepeatedMakerBothRejectExcessBeforeStateMutation() {
        try (var runtime = new TradingRuntimeState()) {
            var identities = new RuntimeIdentityRegistry();
            int symbol = identities.symbolId("BTC-USDT");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.putInstrument(instrument());
            var maker = order(10, 20, symbol, CoreOrderSide.SELL, 3);
            var taker = order(11, 21, symbol, CoreOrderSide.BUY, 5);
            runtime.putOrder(maker); runtime.putOrder(taker);
            var slot = new MatcherSettlementPlan();
            assertThatThrownBy(() -> MatcherSettlementPlan.buildInto(slot, 1, 11, 21, 11, 0,
                    fill(4), runtime, identities)).isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds");
            var repeated = new CoreMatchingResult(true, "SUCCESS", List.of(), 0, true,
                    fill(1).nativeCommand(), fill(1).matcherPrefix(), null,
                    List.of(MatcherEventFixtures.trade(10, 20, 100, 2, false, false),
                            MatcherEventFixtures.trade(10, 20, 100, 2, false, false)), fill(1).marketData());
            assertThatThrownBy(() -> MatcherSettlementPlan.buildInto(slot, 1, 11, 21, 11, 0,
                    repeated, runtime, identities)).isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds");
            assertThat(MatcherSettlementPlan.buildInto(slot, 1, 11, 21, 11, 0,
                    fill(3), runtime, identities).tradeCount()).isEqualTo(1);
            assertThat(runtime.order(10)).isSameAs(maker);
            assertThat(runtime.order(11)).isSameAs(taker);
        }
    }

    private static OrderRuntime order(long id,long user,int symbol,CoreOrderSide side,long qty) {
        return new OrderRuntime(id,user,symbol,1,side,100,false,CoreMarginMode.CROSS,CorePositionSide.NET,
                CoreOrderType.LIMIT,CoreTimeInForce.GTC,0,0,qty,0,qty,false);
    }
    private static CoreMatchingResult fill(long quantity) { return fill(quantity, 1); }
    private static CoreMatchingResult fill(long quantity, long sequence) {
        return new CoreMatchingResult(true,"SUCCESS",List.of(),0,true,
                new CoreMatchingResult.NativeCommand(0,0,0,0,0,0,0,0,-1),new CoreMatchingResult.MatcherPrefix(0,0),null,
                List.of(MatcherEventFixtures.trade(10,20,100,quantity,false,false)),
                new MatcherResult.MarketData(List.of(),List.of(),0,0)).withCoreSequence(sequence);
    }
    private static CoreInstrumentState instrument() {
        return new CoreInstrumentState("BTC-USDT",1,ContractType.LINEAR_PERPETUAL,"BTC","USDT","USDT",
                1,1,1_000_000,100_000,50_000,-10,25,0,null,0,10_000_000,Long.MAX_VALUE,0,1,
                List.of(new CoreRiskLimitBracket(1,0,Long.MAX_VALUE,10_000_000,100_000,50_000)));
    }
}
