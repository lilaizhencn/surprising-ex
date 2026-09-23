package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreOrderStatus;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LaneTerminalSummaryTest {
    @Test void mixedBatchRetainsOnlyTerminalsAfterSummaryGrowthAndReuse() {
        var state = new TradingRuntimeState();
        var changes = new LaneCommitDelta();
        var expected = new ArrayList<Long>();
        for (long id = 1; id <= 40; id++) {
            var order = CoreStateTestFixtures.order(id, 7, 5, 10);
            if ((id & 1) == 0) {
                order = order.withStatus(CoreOrderStatus.CANCELED, 2);
                expected.add(id);
            }
            changes.putOrder(id, order);
        }
        changes.preparePublication();
        var actual = new ArrayList<Long>();
        changes.commitTerminalToOwner(state, 0,
                (orderId, userId, clientOrderId, sequence) -> actual.add(orderId), 9, null, null);
        assertThat(actual).containsExactlyElementsOf(expected);
        state.clearChangedKeys();
        changes.clear();
        changes.putOrder(41, CoreStateTestFixtures.order(41, 7, 5, 10));
        changes.preparePublication();
        actual.clear();
        changes.commitTerminalToOwner(state, 0,
                (orderId, userId, clientOrderId, sequence) -> actual.add(orderId), 10, null, null);
        assertThat(actual).isEmpty();
    }
    @Test void publishedOpenTerminalAndRemovedOrdersRemainCorrectAcrossReuse() {
        var state = new TradingRuntimeState();
        var changes = new LaneCommitDelta();
        var retained = new ArrayList<Long>();
        TradingRuntimeState.TerminalOrderSink sink =
                (orderId, userId, clientOrderId, sequence) -> retained.add(orderId);
        var open = CoreStateTestFixtures.order(11, 7, 5, 10);
        for (var status : new CoreOrderStatus[]{CoreOrderStatus.OPEN, CoreOrderStatus.FILLED,
                CoreOrderStatus.OPEN, CoreOrderStatus.CANCELED, CoreOrderStatus.REJECTED}) {
            changes.putOrder(11, open.withStatus(status, 2));
            changes.preparePublication();
            retained.clear();
            changes.commitTerminalToOwner(state, 0, sink, 9, null, null);
            if (status.terminal()) assertThat(retained).containsExactly(11L);
            else assertThat(retained).isEmpty();
            assertThat(state.publishedOrders.get(11).status()).isEqualTo(status);
            state.clearChangedKeys();
            changes.clear();
        }
        changes.putOrder(11, null);
        changes.preparePublication();
        retained.clear();
        changes.commitTerminalToOwner(state, 0, sink, 10, null, null);
        assertThat(retained).isEmpty();
        assertThat(state.publishedOrders.get(11)).isNull();
    }

    @Test void preparedPublicationVisitsTerminalOrdersWithoutLaneSummary() {
        var state = new TradingRuntimeState();
        var changes = new LaneCommitDelta();
        changes.putOrder(11, CoreStateTestFixtures.order(11, 7, 5, 10).withStatus(CoreOrderStatus.CANCELED, 2));
        changes.preparePublication();
        var retained = new ArrayList<Long>();
        changes.commitTerminalToOwner(state, 0,
                (orderId, userId, clientOrderId, sequence) -> retained.add(orderId), 9, null, null);
        assertThat(retained).containsExactly(11L);
        assertThat(state.publishedOrders.get(11).status()).isEqualTo(CoreOrderStatus.CANCELED);
    }
}
