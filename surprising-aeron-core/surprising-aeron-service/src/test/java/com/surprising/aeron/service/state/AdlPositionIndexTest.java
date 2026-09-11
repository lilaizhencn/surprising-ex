package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CorePositionSide;
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
        for (int quantity = 2; quantity < 100; quantity++) {
            var current = position("USDT", quantity % 2 == 0 ? quantity : -quantity);
            index.apply(1, previous, current);
            assertThat(index.value(1)).isSameAs(current);
            assertThat(assets.get("USDT")).isSameAs(members);
            assertThat(index.positions("USDT")).hasSize(1);
            previous = current;
        }
    }

    @Test
    void updatesMembershipOnCloseReopenAssetChangeAndRemoval() {
        var index = emptyIndex();
        var open = position("USDT", 10);
        var closed = position("USDT", 0);
        var moved = position("BTC", -5);
        index.apply(1, null, open);
        index.apply(1, open, closed);
        assertThat(index.positions("USDT")).isEmpty();
        assertThat(index.value(1)).isSameAs(closed);
        index.apply(1, closed, open);
        assertThat(index.positions("USDT")).hasSize(1);
        index.apply(1, open, moved);
        assertThat(index.positions("USDT")).isEmpty();
        assertThat(index.positions("BTC")).hasSize(1);
        index.apply(1, moved, null);
        assertThat(index.positions("BTC")).isEmpty();
        assertThat(index.value(1)).isNull();
        index.apply(1, null, closed);
        index.apply(1, closed, position("BTC", 0));
        assertThat(index.positions("BTC")).isEmpty();
    }

    @Test
    void rejectsStalePreviousWithoutDamagingOtherPositionsOrCurrentValue() {
        var index = emptyIndex();
        var first = position("USDT", 1);
        var second = new RuntimePositionIndexValue(8, "BTC-USDT", "USDT", CorePositionSide.NET, 2);
        index.apply(1, null, first);
        index.apply(2, null, second);
        assertThatThrownBy(() -> index.apply(1, position("USDT", 1), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(index.value(1)).isSameAs(first);
        assertThat(index.positions("USDT")).hasSize(2);
        index.apply(1, first, null);
        assertThat(index.value(2)).isSameAs(second);
        assertThat(index.positions("USDT")).hasSize(1);
    }

    private static AdlPositionIndex emptyIndex() {
        return new AdlPositionIndex(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                new RuntimeIdentityRegistry());
    }

    private static RuntimePositionIndexValue position(String asset, long quantity) {
        return new RuntimePositionIndexValue(7, "BTC-USDT", asset, CorePositionSide.NET, quantity);
    }
}
