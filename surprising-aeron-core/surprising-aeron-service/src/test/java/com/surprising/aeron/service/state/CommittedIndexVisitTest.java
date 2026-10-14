package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.*;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CommittedIndexVisitTest {
    @Test void directAndBorrowedLaneTombstonesKeepPublicationOrderUntilTheCommitClearsThem() {
        try (var state = new TradingRuntimeState()) {
            state.setMetadata(ProductLine.SPOT, 1);
            var visited = new ArrayList<String>();
            var consumer = new TradingRuntimeState.ChangedIndexConsumer() {
                @Override public void order(long id, OrderRuntime before, OrderRuntime after) {
                    assertThat(before).isNull(); assertThat(after).isNull(); visited.add("order:" + id);
                }
                @Override public void position(long id, PositionRuntime before, PositionRuntime after) {
                    assertThat(before).isNull(); assertThat(after).isNull(); visited.add("position:" + id);
                }
                @Override public void liquidation(long id, LiquidationRuntime before, LiquidationRuntime after) {
                    assertThat(before).isNull(); assertThat(after).isNull(); visited.add("liquidation:" + id);
                }
                @Override public void algoOrder(long id, CoreAlgoOrderState before, CoreAlgoOrderState after) {
                    assertThat(before).isNull(); assertThat(after).isNull(); visited.add("algo:" + id);
                }
                @Override public void triggerOrder(long id, CoreTriggerOrderState before, CoreTriggerOrderState after) {
                    assertThat(before).isNull(); assertThat(after).isNull(); visited.add("trigger:" + id);
                }
            };
            state.changedOrders.put(11, null);
            state.changedPositions.put(21, null);
            state.visitChangedIndexes(consumer);
            assertThat(visited).containsExactly("order:11", "position:21");
            state.clearChangedKeys(); visited.clear();

            var firstOrders = new RuntimeOrderChangeBuffer();
            var lastOrders = new RuntimeOrderChangeBuffer();
            var firstPositions = new RuntimePositionChangeBuffer();
            var lastPositions = new RuntimePositionChangeBuffer();
            firstOrders.put(30, null); lastOrders.put(33, null);
            firstPositions.putIndexed(40, null, true, null);
            lastPositions.putIndexed(43, null, true, null);
            state.changedOrders.adopt(3, lastOrders); state.changedOrders.adopt(0, firstOrders);
            state.changedPositions.adopt(3, lastPositions); state.changedPositions.adopt(0, firstPositions);
            firstOrders.put(999, null); firstPositions.put(998, null);
            state.changedLiquidations.put(50, null);
            state.changedAlgoOrders.put(60, null);
            state.changedTriggerOrders.put(70, null);
            for (int repeat = 0; repeat < 2; repeat++) {
                visited.clear();
                state.visitChangedIndexes(consumer);
                assertThat(visited).containsExactly("order:30", "order:33", "position:40", "position:43",
                        "liquidation:50", "algo:60", "trigger:70");
            }
            state.clearChangedKeys(); visited.clear(); state.visitChangedIndexes(consumer);
            assertThat(visited).isEmpty();
            assertThat(firstOrders.containsKey(999)).isTrue();
            assertThat(firstPositions.containsKey(998)).isTrue();
        }
    }
}
