package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class CommittedTradeReplayTest {
    @Test void conflictingRetryRemainsARejectedStatisticsAttemptInsteadOfBorrowingTheOriginalSuccess() {
        var line=ProductLine.SPOT;
        try(var replay=new CommittedTradeReplay(line,null)) {
            send(replay,line,CoreMessageType.REGISTER_INSTRUMENT,0,TradingCommandCodec.encodeRegisterInstrument(
                    new RegisterInstrumentCommand("604",ContractType.SPOT.ordinal(),"BTC","USDT","USDT",1,1,1,100000,50000,0,0,0,-1,0)));
            send(replay,line,CoreMessageType.ADJUST_BALANCE,7,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",1000000)));
            place(replay,line,7,201,CoreOrderSide.BUY);var original=replay.commandResult();
            long seq=++sequence;
            var conflict=new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER,original.commandId(),line,
                    CommandSource.OPERATIONS,991,seq,7,TIME+seq,seq),TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(201,"604",CoreOrderSide.BUY,
                            101,2,false,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"replay-201")));
            replay.apply(CoreMessageCodec.encode(conflict),TIME+seq,seq);
            assertThat(replay.commandResult().code()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
            assertThat(replay.commandResult().status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(original.code()).isEqualTo(CoreResultCode.NONE);
            seq=++sequence;
            var malformed=new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER,original.commandId(),line,
                    CommandSource.OPERATIONS,991,seq,7,TIME+seq,seq),new byte[]{1});
            replay.apply(CoreMessageCodec.encode(malformed),TIME+seq,seq);
            assertThat(replay.commandResult().code()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
        }
    }
    @Test void capturesTerminalTriggersAndReconstructsThemWithoutChangingReplayBusinessState() {
        var line = ProductLine.OPTION;
        byte[] snapshot;
        try (var replay = new CommittedTradeReplay(line, null)) {
            send(replay, line, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("604",
                            ContractType.VANILLA_OPTION.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                            100_000, 50_000, 0, 0, 2_000_000_000_000L, 0, 100)));
            for (long user : new long[]{7, 8}) send(replay, line, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000_000)));
            send(replay, line, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("604", 100, 100, 100, 1, TIME)));
            place(replay, line, 7, 201, CoreOrderSide.SELL);
            place(replay, line, 8, 202, CoreOrderSide.BUY);
            var trigger = new CoreTriggerOrderStateView(Long.MAX_VALUE, line, 8, "retained-tp", "", "604", CoreOrderSide.SELL,
                    CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL, 200, 0, 0, 0, 0, 0,
                    CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", 0, 0, 0, 0, 1);
            var created = send(replay, line, CoreMessageType.PLACE_TRIGGER_ORDER, 8, CoreTriggerOrderCodec.encodeState(trigger));
            assertThat(created).anySatisfy(f -> assertThat(f.kind()).isEqualTo(RealtimeFrame.Kind.TRIGGER));
            var canceled = send(replay, line, CoreMessageType.CANCEL_TRIGGER_ORDER, 8, CoreTriggerOrderCodec.encodeId(Long.MAX_VALUE));
            assertThat(canceled.stream().filter(f -> f.kind() == RealtimeFrame.Kind.TRIGGER)
                    .flatMap(f -> CoreTriggerOrderCodec.decodeList(f.payload()).stream()))
                    .anySatisfy(v -> assertThat(v.status()).isEqualTo(CoreTriggerOrderStatus.CANCELED));
            snapshot = replay.snapshot();
        }
        try (var restored = new CommittedTradeReplay(line, snapshot)) {
            long before = restored.businessHash();
            var activity=restored.currentTradingActivity(TIME);
            assertThat(activity.usersWithPositions()).isEqualTo(2);
            assertThat(activity.openPositions()).isEqualTo(2);
            assertThat(activity.openOrders()).isZero();
            assertThat(activity.pendingTriggers()).isZero();
            assertThat(activity.instruments()).singleElement().satisfies(v->{
                assertThat(v.longQuantitySteps()).isEqualTo(java.math.BigInteger.valueOf(2));
                assertThat(v.shortQuantitySteps()).isEqualTo(java.math.BigInteger.valueOf(2));
            });
            var retained = restored.retainedTriggersBefore(0, 1);
            assertThat(retained).hasSize(1);
            assertThat(retained.getFirst().triggerOrderId()).isEqualTo(Long.MAX_VALUE);
            assertThat(retained.getFirst().status()).isEqualTo(CoreTriggerOrderStatus.CANCELED);
            assertThat(restored.retainedTriggersBefore(Long.MAX_VALUE, 1)).isEmpty();
            assertThat(restored.businessHash()).isEqualTo(before);
        }
    }
    @Test void committedCancelBatchRetainsItsMixedOutcomesBeyondTheCoreCache() {
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            send(replay, ProductLine.SPOT, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("604", 0,
                            "BTC", "USDT", "USDT", 1, 1, 1, 100000, 50000, 0, 0, 0, -1, 0)));
            send(replay, ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1000000)));
            place(replay, ProductLine.SPOT, 7, 201, CoreOrderSide.BUY);
            send(replay, ProductLine.SPOT, CoreMessageType.CANCEL_ORDER_BATCH, 7,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(201), new CancelOrderCommand(999)))));
            var receipt = replay.commandResult();
            assertThat(receipt.code()).isEqualTo(CoreResultCode.NONE);
            assertThat(receipt.cancelResults()).containsExactly(
                    new CommittedTradeReplay.CancelBatchItem(0, 201, true, "NONE"),
                    new CommittedTradeReplay.CancelBatchItem(1, 999, false, "ORDER_NOT_FOUND"));
            for (int i = 0; i < 140; i++) send(replay, ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1)));
            assertThat(receipt.cancelResults().get(1).code()).isEqualTo("ORDER_NOT_FOUND");
        }
    }

    @Test void spotAlgoProgressFollowsCommittedChildFillsAndSurvivesReplayRestart() {
        byte[] snapshot;
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            send(replay, ProductLine.SPOT, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("604", 0,
                            "BTC", "USDT", "USDT", 1, 1, 1, 100000, 50000, 0, 0, 0, -1, 0)));
            send(replay, ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1000000)));
            send(replay, ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, 8,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 1000000)));
            send(replay, ProductLine.SPOT, CoreMessageType.UPSERT_ALGO_ORDER, 7, CoreAlgoOrderCodec.encode(algo(0,1,List.of())));
            assertThat(replay.algoChanges()).singleElement().satisfies(v -> {
                assertThat(v.algoOrderId()).isEqualTo(701); assertThat(v.activeQuantitySteps()).isZero();
            });
            send(replay, ProductLine.SPOT, CoreMessageType.PLACE_ORDER, 7, TradingCommandCodec.encodePlaceOrder(
                    new PlaceOrderCommand(201,"604",CoreOrderSide.BUY,100,2,false,CoreMarginMode.CROSS,
                            CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"algo-701-0")));
            assertThat(replay.algoChanges()).isEmpty();
            send(replay, ProductLine.SPOT, CoreMessageType.UPSERT_ALGO_ORDER, 7, CoreAlgoOrderCodec.encode(algo(1,2,List.of(201L))));
            assertThat(replay.algoChanges()).singleElement().satisfies(v -> {
                assertThat(v.activeQuantitySteps()).isEqualTo(2);assertThat(v.executedQuantitySteps()).isZero();
            });
            snapshot=replay.snapshot();
        }
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT,snapshot)) {
            long before=replay.businessHash();
            var initial=new ArrayList<CoreAlgoOrderView>();replay.forEachRetainedAlgo(initial::add);
            assertThat(initial).hasSize(1);assertThat(replay.businessHash()).isEqualTo(before);
            place(replay,ProductLine.SPOT,8,202,CoreOrderSide.SELL);
            assertThat(replay.algoChanges()).singleElement().satisfies(v -> {
                assertThat(v.revision()).isEqualTo(2);assertThat(v.executedQuantitySteps()).isEqualTo(2);
                assertThat(v.activeQuantitySteps()).isZero();assertThat(v.activeChildOrderCount()).isZero();
            });
            try (var afterFill = new CommittedTradeReplay(ProductLine.SPOT,replay.snapshot())) {
                var retained = new ArrayList<CoreAlgoOrderView>(); afterFill.forEachRetainedAlgo(retained::add);
                assertThat(retained).singleElement().satisfies(v -> assertThat(v.executedQuantitySteps()).isEqualTo(2));
                assertThat(afterFill.businessHash()).isEqualTo(replay.businessHash());
            }
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,CoreAlgoOrderCodec.encode(algo(1,2,List.of(201L))));
            assertThat(replay.commandResult().status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(replay.algoChanges()).isEmpty();
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,CoreAlgoOrderCodec.encode(algo(3,3,List.of(201L))));
            assertThat(replay.algoChanges()).singleElement().satisfies(v -> assertThat(v.statusCode()).isEqualTo(3));
            place(replay,ProductLine.SPOT,8,203,CoreOrderSide.SELL);
            assertThat(replay.algoChanges()).isEmpty();
        }
    }

    @Test void immediatelyFilledFirstChildCanLinkAfterRetirementAndSnapshotRestore() {
        byte[] snapshot;
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT,null)) {
            send(replay,ProductLine.SPOT,CoreMessageType.REGISTER_INSTRUMENT,0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("604",0,"BTC","USDT","USDT",1,1,1,100000,50000,0,0,0,-1,0)));
            send(replay,ProductLine.SPOT,CoreMessageType.ADJUST_BALANCE,7,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",1000000)));
            send(replay,ProductLine.SPOT,CoreMessageType.ADJUST_BALANCE,8,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC",1000000)));
            place(replay,ProductLine.SPOT,8,202,CoreOrderSide.SELL);
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,CoreAlgoOrderCodec.encode(algo(0,1,List.of())));
            send(replay,ProductLine.SPOT,CoreMessageType.PLACE_ORDER,7,TradingCommandCodec.encodePlaceOrder(
                    new PlaceOrderCommand(201,"604",CoreOrderSide.BUY,100,2,false,CoreMarginMode.CROSS,
                            CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"algo-701-0")));
            assertThat(replay.algoChanges()).singleElement().satisfies(v -> {
                assertThat(v.executedQuantitySteps()).isEqualTo(2);assertThat(v.childOrderIds()).containsExactly(201L);
            });
            snapshot=replay.snapshot();
        }
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT,snapshot)) {
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,CoreAlgoOrderCodec.encode(algo(1,2,List.of(201L))));
            assertThat(replay.commandResult().status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(replay.algoChanges()).singleElement().satisfies(v -> {
                assertThat(v.childOrderIds()).containsExactly(201L);assertThat(v.executedQuantitySteps()).isEqualTo(2);
                assertThat(v.activeQuantitySteps()).isZero();
            });
        }
    }

    @Test void cancellationCannotBeUndoneByALateSchedulerUpdate() {
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT,null)) {
            send(replay,ProductLine.SPOT,CoreMessageType.REGISTER_INSTRUMENT,0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("604",0,"BTC","USDT","USDT",1,1,1,100000,50000,0,0,0,-1,0)));
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,CoreAlgoOrderCodec.encode(algo(0,1,List.of())));
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,CoreAlgoOrderCodec.encode(algo(2,2,List.of())));
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,CoreAlgoOrderCodec.encode(algo(1,3,List.of())));
            assertThat(replay.commandResult().code()).isEqualTo(CoreResultCode.INVALID_ALGO_ORDER_REVISION);
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,CoreAlgoOrderCodec.encode(algo(3,3,List.of())));
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,CoreAlgoOrderCodec.encode(algo(1,4,List.of())));
            assertThat(replay.commandResult().code()).isEqualTo(CoreResultCode.INVALID_ALGO_ORDER_REVISION);
            var retained = new ArrayList<CoreAlgoOrderView>();replay.forEachRetainedAlgo(retained::add);
            assertThat(retained).singleElement().satisfies(v -> {assertThat(v.statusCode()).isEqualTo(3);assertThat(v.revision()).isEqualTo(3);});
        }
    }

    @Test void cancelRequestedParentRejectsALateChildBeforeItCanReserveFunds() {
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT,null)) {
            send(replay,ProductLine.SPOT,CoreMessageType.REGISTER_INSTRUMENT,0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("604",0,
                            "BTC","USDT","USDT",1,1,1,100000,50000,0,0,0,-1,0)));
            send(replay,ProductLine.SPOT,CoreMessageType.ADJUST_BALANCE,7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",1000000)));
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,CoreAlgoOrderCodec.encode(algo(0,1,List.of())));
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,CoreAlgoOrderCodec.encode(algo(2,2,List.of())));
            send(replay,ProductLine.SPOT,CoreMessageType.PLACE_ORDER,7,TradingCommandCodec.encodePlaceOrder(
                    new PlaceOrderCommand(201,"604",CoreOrderSide.BUY,100,2,false,CoreMarginMode.CROSS,
                            CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"algo-701-0")));
            assertThat(replay.commandResult().code()).isEqualTo(CoreResultCode.INVALID_ALGO_CHILD);
            try (var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT,replay.snapshot())) {
                var user = CoreTestCompletion.userState(restored,7);
                assertThat(user.reservations()).isEmpty();
                assertThat(user.balances()).allMatch(v->v.lockedUnits()==0);
                assertThat(user.balances().stream().mapToLong(v->v.availableUnits()).sum()).isEqualTo(1000000);
            }
        }
    }

    private CoreAlgoOrderView algo(int status,long revision,List<Long> children) {
        return new CoreAlgoOrderView(701,7,"algo-client-701","604",0,CoreOrderSide.BUY,100,4,2,5,20,
                CoreMarginMode.CROSS,CorePositionSide.NET,false,false,CoreTimeInForce.GTC,status,
                children.isEmpty()?0:children.getLast(),"",TIME,TIME+5000,0,TIME,TIME+revision,revision,children,0,0,0);
    }

    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @Test void spotAdmissionRejectionWithoutAnOrderStillCarriesTheClientIdentity() {
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            send(replay, ProductLine.SPOT, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("604",
                            ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                            100000, 50000, 0, 0, 0, -1, 0)));
            send(replay, ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1)));
            assertThat(place(replay, ProductLine.SPOT, 7, 201, CoreOrderSide.BUY)).isEmpty();
            assertThat(replay.commandResult()).satisfies(result -> {
                assertThat(result.clientOrderId()).isEqualTo("replay-201");
                assertThat(result.instrumentId()).isEqualTo("604");
                assertThat(result.status()).isEqualTo(ResponseStatus.REJECTED);
                assertThat(result.code()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
            });
        }
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void capturesCommittedMakerAndTakerOrdersAndResumesFromSnapshot(ProductLine line) {
        byte[] snapshot;
        try (var replay = new CommittedTradeReplay(line, null)) {
            var type = ContractType.valueOf(line.contractTypeCode());
            send(replay, line, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("604",
                            type.ordinal(), "BTC", "USDT", type.isInverse() ? "BTC" : "USDT", 1, 1,
                            type.isInverse() ? 1000 : 1, 100_000, 50_000, 0, 0,
                            type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0,
                            type.isOption() ? 0 : -1, type.isOption() ? 100 : 0)));
            for (long user : new long[]{7, 8}) for (String asset : List.of("BTC", "USDT"))
                send(replay, line, CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 1_000_000)));
            if (line != ProductLine.SPOT) send(replay, line, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(line == ProductLine.OPTION
                            ? new ApplyMarkPriceCommand("604", 100, 100, 100, 1, TIME)
                            : new ApplyMarkPriceCommand("604", 100, 1, TIME)));
            var resting = place(replay, line, 7, 201, CoreOrderSide.SELL);
            assertThat(replay.commandResult()).satisfies(result -> {
                assertThat(result.userId()).isEqualTo(7);
                assertThat(result.clientOrderId()).isEqualTo("replay-201");
                assertThat(result.instrumentId()).isEqualTo("604");
                assertThat(result.code()).isEqualTo(CoreResultCode.NONE);
            });
            assertThat(resting).hasSize(1);
            assertThat(order(resting.getFirst()).status()).isEqualTo("OPEN");
            place(replay, line, 7, 209, CoreOrderSide.SELL);
            var batchCancel = send(replay, line, CoreMessageType.CANCEL_ORDER_BATCH, 7,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(999), new CancelOrderCommand(209)))));
            assertThat(batchCancel.stream().filter(f -> f.kind() == RealtimeFrame.Kind.ORDER)
                    .map(CommittedTradeReplayTest::order)).anySatisfy(o -> {
                        assertThat(o.orderId()).isEqualTo(209);
                        assertThat(o.status()).isEqualTo("CANCELED");
                    });
            var amended = send(replay, line, CoreMessageType.AMEND_ORDER_BATCH, 7,
                    TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                            new AmendOrderCommand(201, 211, "repriced-211", 99L, null, null, null)))));
            assertThat(amended.stream().filter(f -> f.kind() == RealtimeFrame.Kind.ORDER)
                    .map(CommittedTradeReplayTest::order)).anySatisfy(o -> {
                        assertThat(o.orderId()).isEqualTo(211);
                        assertThat(o.priceTicks()).isEqualTo(99);
                    });
            snapshot = replay.snapshot();
        }
        try (var restored = new CommittedTradeReplay(line, snapshot)) {
            var filled = place(restored, line, 8, 202, CoreOrderSide.BUY);
            assertThat(restored.commandResult().code()).isEqualTo(CoreResultCode.NONE);
            assertThat(restored.commandResult().status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(filled.stream().filter(f -> f.kind() == RealtimeFrame.Kind.TRADE)).hasSize(1);
            var orders = filled.stream().filter(f -> f.kind() == RealtimeFrame.Kind.ORDER).map(CommittedTradeReplayTest::order).toList();
            assertThat(orders).hasSize(2).allSatisfy(o -> {
                assertThat(o.status()).isEqualTo("FILLED");
                assertThat(o.executedQuantitySteps()).isEqualTo(2);
                assertThat(o.remainingQuantitySteps()).isZero();
                assertThat(o.getAveragePriceTicks()).isEqualTo("99");
                assertThat(o.instrumentId()).isEqualTo("604");
                assertThat(o.productLine()).isEqualTo(line);
            });
            place(restored, line, 7, 203, CoreOrderSide.SELL);
            var canceled = send(restored, line, CoreMessageType.CANCEL_ORDER, 7,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(203)));
            assertThat(canceled).hasSize(1);
            assertThat(order(canceled.getFirst()).status()).isEqualTo("CANCELED");
            assertThat(restored.exportSequence()).isPositive();
            // A rejected cancellation must not manufacture an order or advance its revision.
            assertThat(send(restored, line, CoreMessageType.CANCEL_ORDER, 8,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(999)))).isEmpty();
            assertThat(restored.commandResult().status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(restored.commandResult().code()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
        }
    }

    @Test void committedRejectionWithoutAnOrderHasAnImmutableReceiptAfterTheCacheMovesOn() {
        CommittedTradeReplay.CommittedCommandResult rejected;
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            assertThat(send(replay, ProductLine.SPOT, CoreMessageType.CANCEL_ORDER, 7,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(999)))).isEmpty();
            rejected = replay.commandResult();
            assertThat(rejected.commandId()).isEqualTo(new UUID(99, sequence));
            assertThat(rejected.userId()).isEqualTo(7);
            assertThat(rejected.code()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
            for (int i = 0; i < 140; i++) send(replay, ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1)));
            assertThat(replay.commandResult().commandId()).isNotEqualTo(rejected.commandId());
        }
        assertThat(rejected.code()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
        assertThat(rejected.clusterPosition()).isEqualTo(1);
    }

    private List<RealtimeFrame> place(CommittedTradeReplay replay, ProductLine line, long user, long id, CoreOrderSide side) {
        return send(replay, line, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(id, "604", side, 100, 2, false, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "replay-" + id)));
    }

    private List<RealtimeFrame> send(CommittedTradeReplay replay, ProductLine line, CoreMessageType type, long user, byte[] payload) {
        long seq = ++sequence;
        var command = new CoreMessage(CoreMessageHeader.command(type, new UUID(99, seq), line,
                CommandSource.OPERATIONS, 991, seq, user, TIME + seq, seq), payload);
        var frames = replay.apply(CoreMessageCodec.encode(command), TIME + seq, seq);
        assertThat(frames).allSatisfy(frame ->
                assertThat(RealtimeFrameCodec.decode(RealtimeFrameCodec.encode(frame))).isEqualTo(frame));
        return frames;
    }

    private static CoreOrderStateView order(RealtimeFrame frame) {
        assertThat(frame.kind()).isEqualTo(RealtimeFrame.Kind.ORDER);
        return CoreStateQueryCodec.decodeOrderState(frame.payload());
    }
}
