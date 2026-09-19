package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.instrument.CoreInstrumentState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdmissionReceiptRingTest {
    @Test
    void transfersImmutableVersionAndClearsReferenceBeforeSlotReuse() throws Exception {
        var ring = new AdmissionReceiptRing(1);
        var references = AdmissionReceiptRing.class.getDeclaredField("admittedOrders");
        references.setAccessible(true);
        try (var runtime = new TradingRuntimeState()) {
            for (int sequence = 1; sequence <= 64; sequence++) {
                var event = preparedEvent(runtime, sequence);
                var laneOrder = new OrderRuntime(11, 7, 0, 100);
                var receipt = laneOrder.snapshot();
                boolean accepted = sequence % 2 == 1;
                ring.publish(sequence, 11, 1, 100, accepted, 0, accepted ? receipt : null);
                assertThat(ring.hasCapacity()).isFalse();
                assertThatThrownBy(() -> ring.publish(100, 11, 1, 100, true, 0, receipt))
                        .hasMessageContaining("full");
                ring.await(sequence, event);
                assertThat(ring.hasCapacity()).isTrue();
                assertThat((Object[]) references.get(ring)).containsOnlyNulls();
                assertThat(event.admittedOrder()).isSameAs(accepted ? receipt : null);
                laneOrder.applyCommitMetadataInPlace(123, 456);
                assertThat(receipt.clusterPosition()).isZero();
                runtime.releaseMatcherSettlementChanges(event.takeChanges());
            }
        }
    }

    @Test
    void rejectsMissingOrMismatchedOrderWithoutConsumingCapacity() {
        var ring = new AdmissionReceiptRing(1);
        var receipt = new OrderRuntime(11, 7, 0, 100).snapshot();
        assertThatThrownBy(() -> ring.publish(1, 11, 1, 100, true, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ring.publish(1, 12, 1, 100, true, 0, receipt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ring.publish(1, 11, 1, 100, false, 0, receipt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ring.hasCapacity()).isTrue();
    }

    private static MatcherSettlementEvent preparedEvent(TradingRuntimeState runtime, long sequence) {
        var instrument = CoreInstrumentState.from(ProductLine.LINEAR_PERPETUAL,
                new UpsertInstrumentCommand("BTC-USDT", 1, ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        var intent = new PlaceOrderCommand(11, "BTC-USDT", 1, CoreOrderSide.BUY, 100, 1,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "client-11");
        var resolved = new ResolvedPlaceOrder(intent, instrument, 0, 100, 100, 100,
                1, ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 0, 1);
        var event = new MatcherSettlementEvent();
        event.batchStorage(1);
        int lane = runtime.topology().accountLaneId(7);
        event.prepareDirect(sequence, 1L << lane, 0, 0, new UUID(1, sequence), 0,
                runtime, new RuntimeIdentityRegistry(), 1, List.of(), null);
        event.admissionRoute(lane);
        event.admissionOrder(resolved, 7);
        return event;
    }
}
