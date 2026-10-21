package com.surprising.aeron.service.orchestration.trade;

import com.surprising.aeron.service.orchestration.result.ResponseArena;
import com.surprising.aeron.service.orchestration.result.ResponseArenaTestAccess;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBatchSlotReuseTest {
    @Test void admissionReceiptBelongsToItsItemAndSurvivesOtherBatchesAndReuse() {
        var first = new OrderBatchPending(1, new ResponseArena(1, 4096));
        var second = new OrderBatchPending(1, new ResponseArena(1, 4096));
        first.addItem(11, 0, 0, new Object());
        second.addItem(12, 0, 0, new Object());
        var live = order(11, 2);
        var receipt = first.captureAdmittedOrder(0, live);
        assertThat(receipt).isSameAs(first.items.getFirst().admittedOrder).isNotSameAs(live);
        var other = second.captureAdmittedOrder(0, live);
        assertThat(other).isNotSameAs(receipt);
        first.clear();
        assertThat(second.items.getFirst().admittedOrder).isSameAs(other);
        assertThat(other.orderId()).isEqualTo(11);
        first.addItem(13, 0, 0, new Object());
        var nextLive = order(13, 3);
        var reused = first.captureAdmittedOrder(0, nextLive);
        assertThat(reused).isSameAs(receipt);
        assertThat(reused.orderId()).isEqualTo(13);
        assertThat(reused.quantitySteps()).isEqualTo(3);
        assertThat(other.orderId()).isEqualTo(11);
        assertThat(live.orderId()).isEqualTo(11);
        first.clear();
        second.clear();
    }

    private static com.surprising.aeron.service.state.order.OrderRuntime order(long id, long quantity) {
        var instrument = com.surprising.aeron.service.state.instrument.CoreInstrument.from(
                com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,
                new com.surprising.aeron.protocol.RegisterInstrumentCommand("1",
                        com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        return new com.surprising.aeron.service.state.order.OrderRuntime(id, 7, 1, instrument,
                com.surprising.aeron.protocol.CoreOrderSide.BUY, 0, false,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                0, 0, quantity, 0, quantity, false);
    }

    @Test void failedBatchReencodingDropsOldPayloadAndReturnsItsLease() {
        var arena = new ResponseArena(1, 4096);
        var batch = new OrderBatchPending(1, arena);
        batch.addItem(1, 0, 0, new Object());
        var item = batch.items.getFirst();
        item.status = com.surprising.aeron.protocol.ResponseStatus.APPLIED;
        item.resultCode = com.surprising.aeron.protocol.CoreResultCode.NONE;
        batch.resultOrder(0, null, null);
        batch.prepareResponse();
        byte[] original = batch.preparedResponse();
        assertThat(original).isNotNull();
        item.resultCode = null;
        org.assertj.core.api.Assertions.assertThatThrownBy(batch::prepareResponse)
                .isInstanceOf(RuntimeException.class);
        assertThat(batch.preparedResponse()).isNull();
        assertThat(batch.preparedResponseLength()).isZero();
        assertThat(batch.hasPreparedResponseSlot()).isFalse();
        batch.clear();
        var reused = arena.acquireSlot(16);
        assertThat(reused.storage()).isSameAs(original);
        arena.release(reused);
    }

    @Test void batchRequiresAnExplicitResponseArena() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OrderBatchPending(1, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("response arena");
    }

    @Test void laneResponseRequiresEveryItemAndIsDetachedFromReusableSlots() {
        var arena = new ResponseArena(1, 4096);
        var batch = new OrderBatchPending(2, arena);
        batch.addItem(1, 0, 0, new Object());
        batch.addItem(2, 0, 0, new Object());
        for (var item : batch.items) {
            item.status = com.surprising.aeron.protocol.ResponseStatus.APPLIED;
            item.resultCode = com.surprising.aeron.protocol.CoreResultCode.NONE;
        }
        batch.resultOrder(0, null, null);
        batch.prepareResponse();
        assertThat(batch.preparedResponse).isNull();
        batch.resultOrder(1, null, null);
        batch.prepareResponse();
        byte[] encoded = batch.preparedResponse;
        int encodedLength = batch.preparedResponseLength;
        assertThat(com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeResult(encoded, encodedLength).items())
                .hasSize(2);
        batch.transferPreparedResponseOwnership();
        batch.clear();
        assertThat(batch.preparedResponse).isNull();
        var next = arena.acquireSlot(encodedLength);
        assertThat(ResponseArenaTestAccess.arenaBacked(next)).isFalse();
        assertThat(next.storage()).isNotSameAs(encoded);
        java.util.Arrays.fill(next.storage(), (byte) 7);
        var decoded = com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeResult(encoded, encodedLength);
        assertThat(decoded.items()).hasSize(2);
        assertThat(decoded.items().getFirst().orderId()).isEqualTo(1);
        arena.release(next);
        arena.release(encoded);
        var reused = arena.acquireSlot(encodedLength);
        assertThat(reused.storage()).isSameAs(encoded);
        arena.release(reused);
    }

    @Test void reuseClearsPriorInputsAndLaneResultsAcrossDifferentBatchSizes() {
        var batch = new OrderBatchPending(20, new ResponseArena(1, 4096));
        var command = new Object();
        batch.addItem(1, 0, 0, command);
        var first = batch.items.getFirst();
        first.laneResultPrepared = true;
        first.executionCount = 3;
        first.resultOrderSymbol = "1";
        first.cancelSymbol = "1";
        for (int i = 2; i <= 20; i++) batch.addItem(i, 0, 0, command);
        var last = batch.items.getLast();
        batch.clear();
        assertThat(first.command).isNull();
        assertThat(last.command).isNull();
        assertThat(first.cancelSymbol).isNull();
        assertThat(first.resultOrderSymbol).isNull();
        batch.addItem(22, 21, 22, "amend");
        assertThat(batch.items.getFirst()).isSameAs(first);
        assertThat(first.laneResultPrepared).isFalse();
        assertThat(first.executionCount).isZero();
        assertThat(first.originalOrderId()).isEqualTo(21);
        assertThat(batch.size()).isOne();
        batch.clear();
        assertThat(first.command).isNull();
    }
}
