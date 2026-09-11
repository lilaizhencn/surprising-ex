package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreOrderStatus;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LaneTerminalSummaryTest {
    @Test void mixedBatchRetainsOnlyTerminalsAfterSummaryGrowthAndReuse() {
        var state = new TradingRuntimeState();
        var changes = new TradingRuntimeState.PublishedLaneChanges();
        var expected = new ArrayList<Long>();
        for (long id = 1; id <= 40; id++) {
            var order = new OrderRuntime(id, 7, 5, 10);
            if ((id & 1) == 0) {
                order = order.withStatus(CoreOrderStatus.CANCELED, 2);
                expected.add(id);
            }
            changes.putOrder(id, order);
        }
        changes.preparePublication(state);
        var actual = new ArrayList<Long>();
        changes.commitTerminalToOwner(state, 0, (order, sequence) -> actual.add(order.orderId()), 9);
        assertThat(actual).containsExactlyElementsOf(expected);
        state.clearChangedKeys();
        changes.clear();
        changes.putOrder(41, new OrderRuntime(41, 7, 5, 10));
        changes.preparePublication(state);
        actual.clear();
        changes.commitTerminalToOwner(state, 0, (order, sequence) -> actual.add(order.orderId()), 10);
        assertThat(actual).isEmpty();
    }
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
            retained.clear();
            changes.commitTerminalToOwner(state, 0, sink, 9);
            if (status.terminal()) assertThat(retained).containsExactly(11L);
            else assertThat(retained).isEmpty();
            assertThat(state.publishedOrders.get(11).status()).isEqualTo(status);
            state.clearChangedKeys();
            changes.clear();
        }
        changes.putOrder(11, null);
        changes.preparePublication(state);
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
