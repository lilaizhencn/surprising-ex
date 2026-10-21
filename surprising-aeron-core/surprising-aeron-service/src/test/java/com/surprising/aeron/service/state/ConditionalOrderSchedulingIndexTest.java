package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreCancelAllAfterStatus;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.state.index.AlgoOrderIndex;
import com.surprising.aeron.service.state.index.CancelAllAfterIndex;
import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterState;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionalOrderSchedulingIndexTest {
    @Test
    void algoFiltersIntersectInDueOrderAndUpdatesRemoveAllOldKeys() {
        var index = new AlgoOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        index.apply(1, algo(1, 7, "10000", 100));
        index.apply(2, algo(2, 8, "10000", 50));
        index.apply(3, algo(3, 7, "2147483647", 100));
        assertThat(index.query(0, "", 0, 10)).containsExactly(2L, 1L, 3L);
        assertThat(index.query(7, "10000", 100, 10)).containsExactly(1L);
        assertThat(index.query(8, "2147483647", 0, 10)).isEmpty();
        assertThat(index.query(0, "10000", 50, 1)).containsExactly(2L);
        assertThat(index.value(3).instrumentId()).isEqualTo(Integer.MAX_VALUE);
        index.apply(1, algo(1, 7, "2147483647", 25));
        assertThat(index.query(7, "10000", 0, 10)).isEmpty();
        assertThat(index.query(7, "2147483647", 0, 10)).containsExactly(1L, 3L);
        index.apply(3, null);
        assertThat(index.containsClient(7, "algo-3")).isFalse();
        index.rebuild(TradingCoreState.empty(ProductLine.SPOT));
        assertThat(index.query(0, "", 0, 10)).isEmpty();
    }

    @Test
    void cancelTimersRetainTimeUserAndScopeOrderingAfterRescheduleAndRemoval() {
        var index = new CancelAllAfterIndex(TradingCoreState.empty(ProductLine.SPOT));
        var values = new java.util.HashMap<com.surprising.aeron.service.state.model.CoreCancelAllAfterKey,
                CoreCancelAllAfterState>();
        for (var timer : List.of(timer(7, "2", 100), timer(7, "10", 100), timer(8, "*", 50))) {
            values.put(timer.key(), timer); index.apply(timer.key(), timer);
        }
        assertThat(index.query(0, "", 100, 10, values)).containsExactly(
                timer(8, "*", 50).key(), timer(7, "10", 100).key(), timer(7, "2", 100).key());
        assertThat(index.query(7, "2", 100, 10, values)).containsExactly(timer(7, "2", 100).key());
        var changed = timer(7, "2", 25);
        values.put(changed.key(), changed); index.apply(changed.key(), changed);
        assertThat(index.query(0, "", 25, 1, values)).containsExactly(changed.key());
        values.remove(changed.key()); index.apply(changed.key(), null);
        assertThat(index.query(7, "2", 0, 10, values)).isEmpty();
        index.rebuild(TradingCoreState.empty(ProductLine.SPOT));
        assertThat(index.query(0, "", 0, 10, values)).isEmpty();
    }

    private static CoreAlgoOrderState algo(long id, long user, String symbol, long due) {
        return new CoreAlgoOrderState(id, user, "algo-" + id, symbol, 0, CoreOrderSide.BUY, 100, 100, 10,
                1, 10, CoreMarginMode.CROSS, CorePositionSide.NET, false, false, CoreTimeInForce.GTC, 1,
                0, "", 1, due, 0, 1, 1, 1, List.of());
    }

    private static CoreCancelAllAfterState timer(long user, String scope, long due) {
        return new CoreCancelAllAfterState(user, scope, 100, CoreCancelAllAfterStatus.ACTIVE, due, 1, 0, 0, 1);
    }
}
