package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapPage;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OrderBookBootstrapLoaderTest {

    @Test
    void loadsAConsistentFullMarketSnapshotAcrossPages() {
        AtomicInteger calls = new AtomicInteger();

        var view = OrderBookBootstrapLoader.load((type, payload) -> {
            assertThat(type).isEqualTo(CoreMessageType.ORDER_BOOK_BOOTSTRAP_QUERY);
            var query = CoreStateQueryCodec.decodeOrderBookBootstrapQuery(payload);
            if (calls.getAndIncrement() == 0) {
                assertThat(query.snapshotId()).isEmpty();
                assertThat(query.symbolCursor()).isEmpty();
                return CoreStateQueryCodec.encodeOrderBookBootstrapPage(new CoreOrderBookBootstrapPage(
                        "00000000-0000-0000-0000-000000000001", 19, "BTC-USDT", false,
                        List.of(level("BTC-USDT", 100))));
            }
            assertThat(query.snapshotId()).isEqualTo("00000000-0000-0000-0000-000000000001");
            assertThat(query.symbolCursor()).isEqualTo("BTC-USDT");
            return CoreStateQueryCodec.encodeOrderBookBootstrapPage(new CoreOrderBookBootstrapPage(
                    "00000000-0000-0000-0000-000000000001", 19, "", true,
                    List.of(level("ETH-USDT", 200))));
        });

        assertThat(calls).hasValue(2);
        assertThat(view.exportSequence()).isEqualTo(19);
        assertThat(view.levels()).extracting(CoreBookLevelView::symbol)
                .containsExactly("BTC-USDT", "ETH-USDT");
    }

    private static CoreBookLevelView level(String symbol, long price) {
        return new CoreBookLevelView(symbol, CoreOrderSide.BUY, price, 1, 1);
    }
}
