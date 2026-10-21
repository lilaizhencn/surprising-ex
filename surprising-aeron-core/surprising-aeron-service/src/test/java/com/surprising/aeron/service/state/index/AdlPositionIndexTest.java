package com.surprising.aeron.service.state.index;

import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.position.RuntimePositionIndexValue;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AdlPositionIndexTest {
    @Test
    void retainsMembershipStorageButPublishesLatestQuantityAndDirection() throws Exception {
        var index = emptyIndex();
        var previous = position("USDT", 1);
        index.apply(1, null, previous);
        var field = AdlPositionIndex.class.getDeclaredField("keysByAsset");
        field.setAccessible(true);
        var assets = (java.util.Map<?, ?>) field.get(index);
        Object members = assets.get("USDT");
        var stored = index.value(1);
        for (int quantity = 2; quantity < 100; quantity++) {
            var current = position("USDT", quantity % 2 == 0 ? quantity : -quantity);
            index.apply(1, index.value(1), current);
            assertThat(index.value(1)).isSameAs(stored);
            assertThat(stored.signedQuantitySteps()).isEqualTo(current.signedQuantitySteps());
            assertThat(assets.get("USDT")).isSameAs(members);
            assertThat(index.positions("USDT")).hasSize(1);
        }
    }

    @Test
    void updatesMembershipOnCloseReopenAssetChangeAndRemoval() {
        var index = emptyIndex();
        var open = position("USDT", 10);
        var closed = position("USDT", 0);
        var moved = position("BTC", -5);
        index.apply(1, null, open);
        index.apply(1, index.value(1), closed);
        assertThat(index.positions("USDT")).isEmpty();
        assertThat(index.value(1).signedQuantitySteps()).isZero();
        index.apply(1, index.value(1), open);
        assertThat(index.positions("USDT")).hasSize(1);
        index.apply(1, index.value(1), moved);
        assertThat(index.positions("USDT")).isEmpty();
        assertThat(index.positions("BTC")).hasSize(1);
        index.apply(1, index.value(1), null);
        assertThat(index.positions("BTC")).isEmpty();
        assertThat(index.value(1)).isNull();
        index.apply(1, null, closed);
        index.apply(1, index.value(1), position("BTC", 0));
        assertThat(index.positions("BTC")).isEmpty();
    }

    @Test
    void rejectsStalePreviousWithoutDamagingOtherPositionsOrCurrentValue() {
        var index = emptyIndex();
        var first = position("USDT", 1);
        var second = new RuntimePositionIndexValue(8, "1", "USDT", CorePositionSide.NET, 2);
        index.apply(1, null, first);
        index.apply(2, null, second);
        var unrelated = emptyIndex();
        unrelated.apply(1, null, position("USDT", 1));
        assertThatThrownBy(() -> index.apply(1, unrelated.value(1), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(index.value(1).signedQuantitySteps()).isEqualTo(1);
        assertThat(index.positions("USDT")).hasSize(2);
        index.apply(1, index.value(1), null);
        assertThat(index.value(2).userId()).isEqualTo(8);
        assertThat(index.value(2).signedQuantitySteps()).isEqualTo(2);
        assertThat(index.positions("USDT")).hasSize(1);
    }

    @Test
    void checksQueryScanLimitBeforeMaterializingCandidateRoutes() {
        var index = emptyIndex();
        index.apply(1, null, position("USDT", 1));
        index.apply(2, null, new RuntimePositionIndexValue(1, "1", "USDT", CorePositionSide.NET, 2));
        assertThatThrownBy(() -> index.positions("USDT", 1))
                .isInstanceOf(com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException.class);
        assertThat(index.positions("USDT", 2)).extracting(AdlPositionIndex.PositionKey::userId).containsExactly(1L, 7L);
        assertThat(index.positions("BTC", 1)).isEmpty();
    }

    private static AdlPositionIndex emptyIndex() {
        return new AdlPositionIndex(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                new RuntimeIdentityRegistry());
    }

    private static RuntimePositionIndexValue position(String asset, long quantity) {
        return new RuntimePositionIndexValue(7, "1", asset, CorePositionSide.NET, quantity);
    }
}
