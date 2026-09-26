package com.surprising.websocket.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static com.surprising.aeron.protocol.CoreOrderSide.*;

import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.CoreOrderBookView;
import java.util.List;
import org.junit.jupiter.api.Test;

class DepthUpdateTest {
    @Test
    void initialSnapshotIncludesBothSidesAndEmptyBook() {
        var update = DepthUpdate.between(null, new CoreOrderBookView(1, List.of(
                new CoreBookLevelView("BTC-USDT", BUY, 100, 2, 1),
                new CoreBookLevelView("BTC-USDT", SELL, 101, 3, 1))));
        assertThat(update.updateType()).isEqualTo("SNAPSHOT");
        assertThat(update.previousSequence()).isNull();
        assertThat(update.sequence()).isEqualTo("1");
        assertThat(update.depth()).isEqualTo(50);
        assertThat(update.bids()).containsExactly(new DepthUpdate.Level(100, 2, 1));
        assertThat(update.asks()).containsExactly(new DepthUpdate.Level(101, 3, 1));
        assertThat(DepthUpdate.between(null, new CoreOrderBookView(0, List.of())).bids()).isEmpty();
    }

    @Test
    void changedLevelsUseAbsoluteQuantityAndRemovedLevelsUseZero() {
        var before = new CoreOrderBookView(10, List.of(
                new CoreBookLevelView("BTC-USDT", BUY, 100, 2, 1),
                new CoreBookLevelView("BTC-USDT", BUY, 99, 5, 1),
                new CoreBookLevelView("BTC-USDT", SELL, 101, 3, 1)));
        var after = new CoreOrderBookView(14, List.of(
                new CoreBookLevelView("BTC-USDT", BUY, 100, 8, 2),
                new CoreBookLevelView("BTC-USDT", BUY, 98, 4, 1),
                new CoreBookLevelView("BTC-USDT", SELL, 101, 3, 1)));
        var delta = DepthUpdate.between(before, after);
        assertThat(delta.updateType()).isEqualTo("DELTA");
        assertThat(delta.previousSequence()).isEqualTo("10");
        assertThat(delta.sequence()).isEqualTo("14");
        assertThat(delta.bids()).containsExactly(new DepthUpdate.Level(100, 8, 2),
                new DepthUpdate.Level(99, 0, 0), new DepthUpdate.Level(98, 4, 1));
        assertThat(delta.asks()).isEmpty();
        var empty = DepthUpdate.between(after, new CoreOrderBookView(15, List.of()));
        assertThat(empty.bids()).allMatch(l -> l.quantitySteps() == 0 && l.orderCount() == 0);
        assertThat(empty.asks()).containsExactly(new DepthUpdate.Level(101, 0, 0));
        var unchanged = DepthUpdate.between(after, new CoreOrderBookView(16, after.levels()));
        assertThat(unchanged.bids()).isEmpty();
        assertThat(unchanged.asks()).isEmpty();
    }
}
