package com.surprising.aeron.service.orchestration;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OrderBatchSlotReuseTest {
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
        assertThat(reused.storage).isSameAs(original);
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
        assertThat(next.arenaBacked).isFalse();
        assertThat(next.storage).isNotSameAs(encoded);
        java.util.Arrays.fill(next.storage, (byte) 7);
        var decoded = com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeResult(encoded, encodedLength);
        assertThat(decoded.items()).hasSize(2);
        assertThat(decoded.items().getFirst().orderId()).isEqualTo(1);
        arena.release(next);
        arena.release(encoded);
        var reused = arena.acquireSlot(encodedLength);
        assertThat(reused.storage).isSameAs(encoded);
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
