package com.surprising.aeron.service.core.change;

import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.core.CoreStateTestFixtures;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.position.Position;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 验证直接及保留 Lane 删除事实更新真实业务索引，提交清理不触碰源缓冲的新命令。 */
class CommittedIndexVisitTest {
    @Test
    void directAndBorrowedLaneCompletedOrdersUpdateIndexesUntilTheCommitClearsThem() {
        try (var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            var state = core.runtimeState();
            var identities = core.identities();
            var indexes = CoreStateTestFixtures.factIndexes(core);
            int symbol = identities.symbolId("1");
            indexes.activeOrders().apply(11, CoreStateTestFixtures.order(11, 7, symbol, 2), identities);
            seedPosition(indexes, identities, 21);
            assertThat(indexes.activeOrders().page(7, "1", Long.MAX_VALUE, 10).orderIds()).containsExactly(11L);
            assertThat(indexes.openInterest().totals()).isNotEmpty();
            state.orderChanges().put(11, null);
            state.positionChanges().put(21, null);
            state.applyLedgerControlPositions(new com.surprising.aeron.service.core.index.RuntimeFactIndexes.PositionUndo());
            state.applyChangedIndexes();
            assertThat(indexes.activeOrders().page(7, "1", Long.MAX_VALUE, 10).orderIds()).isEmpty();
            assertThat(indexes.openInterest().totals()).isEmpty();
            state.clearChangedKeys();

            var firstOrders = new StateChangeBuffer.Orders();
            var lastOrders = new StateChangeBuffer.Orders();
            var firstPositions = new StateChangeBuffer.Positions();
            var lastPositions = new StateChangeBuffer.Positions();
            firstOrders.put(30, null); lastOrders.put(33, null);
            firstPositions.putIndexed(40, null, true, null);
            lastPositions.putIndexed(43, null, true, null);
            state.orderChanges().adopt(3, lastOrders); state.orderChanges().adopt(0, firstOrders);
            state.positionChanges().adopt(3, lastPositions); state.positionChanges().adopt(0, firstPositions);
            firstOrders.put(999, null); firstPositions.put(998, null);
            for (long orderId : new long[]{30, 33})
                indexes.activeOrders().apply(orderId, CoreStateTestFixtures.order(orderId, 7, symbol, 2), identities);
            for (long key : new long[]{40, 43})
                seedPosition(indexes, identities, key);
            for (int repeat = 0; repeat < 2; repeat++) {
                state.applyLedgerControlPositions(new com.surprising.aeron.service.core.index.RuntimeFactIndexes.PositionUndo());
                state.applyChangedIndexes();
                assertThat(indexes.activeOrders().page(7, "1", Long.MAX_VALUE, 10).orderIds()).isEmpty();
                assertThat(indexes.openInterest().totals()).isEmpty();
                assertThat(indexes.positionUsers().higherUserId("1", 0)).isZero();
            }
            state.clearChangedKeys();
            state.applyLedgerControlPositions(new com.surprising.aeron.service.core.index.RuntimeFactIndexes.PositionUndo());
            state.applyChangedIndexes();
            assertThat(firstOrders.containsKey(999)).isTrue();
            assertThat(firstPositions.containsKey(998)).isTrue();
        }
    }
    private static void seedPosition(com.surprising.aeron.service.core.index.RuntimeFactIndexes indexes,
            com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry identities, long key) {
        var changes = new StateChangeBuffer.Positions();
        changes.putIndexed(key, null, true, new Position.IndexValue(7, "1", "USDT", CorePositionSide.NET, 5));
        indexes.applyPositions(changes, identities, null);
    }
}
