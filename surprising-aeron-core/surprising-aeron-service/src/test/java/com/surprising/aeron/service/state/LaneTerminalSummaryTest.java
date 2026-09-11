package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreOrderStatus;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LaneTerminalSummaryTest {
    @Test void publishedOpenTerminalAndRemovedOrdersRemainCorrectAcrossReuse() {
        var state = new TradingRuntimeState();
        var changes = new TradingRuntimeState.PublishedLaneChanges();
        var retained = new ArrayList<Long>();
        TradingRuntimeState.TerminalOrderSink sink = (order, sequence) -> retained.add(order.orderId());
        var open = new OrderRuntime(11, 7, 5, 10);
        for (var status : new CoreOrderStatus[]{CoreOrderStatus.OPEN, CoreOrderStatus.FILLED,
                CoreOrderStatus.OPEN, CoreOrderStatus.CANCELED, CoreOrderStatus.REJECTED}) {
            changes.putOrder(11, open.withStatus(status, 2));
            changes.preparePublication(state);
            assertThat(changes.hasTerminalOrders).isEqualTo(status.terminal());
            retained.clear();
            changes.commitTerminalToOwner(state, 0, sink, 9);
            if (status.terminal()) assertThat(retained).containsExactly(11L);
            else assertThat(retained).isEmpty();
            assertThat(state.publishedOrders.get(11).status()).isEqualTo(status);
            state.clearChangedKeys();
            changes.clear();
            assertThat(changes.hasTerminalOrders).isFalse();
        }
        changes.putOrder(11, null);
        changes.preparePublication(state);
        assertThat(changes.hasTerminalOrders).isFalse();
        retained.clear();
        changes.commitTerminalToOwner(state, 0, sink, 10);
        assertThat(retained).isEmpty();
        assertThat(state.publishedOrders.get(11)).isNull();
    }

    @Test void directPublicationStillVisitsTerminalOrdersWithoutLaneSummary() {
        var state = new TradingRuntimeState();
        var changes = new TradingRuntimeState.PublishedLaneChanges();
        changes.putOrder(11, new OrderRuntime(11, 7, 5, 10).withStatus(CoreOrderStatus.CANCELED, 2));
        var retained = new ArrayList<Long>();
        changes.commitTerminalToOwner(state, 0, (order, sequence) -> retained.add(order.orderId()), 9);
        assertThat(retained).containsExactly(11L);
        assertThat(state.publishedOrders.get(11).status()).isEqualTo(CoreOrderStatus.CANCELED);
    }
}
