package com.surprising.aeron.service.state.snapshot;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PublishedEmbeddedSnapshotTest {
    @Test
    void readsPublishedAlgoWithoutDependingOnInterfaceCodec() throws Exception {
        var expected = new CoreAlgoOrderView(7, 11, "client-7", "1", 0, CoreOrderSide.BUY,
                0, 100, 25, 10, 40, CoreMarginMode.CROSS, CorePositionSide.NET, false, false,
                CoreTimeInForce.IOC, 1, 91, "", "trace", 1, 2, 0, 1, 2, 3, List.of(91L), 25, 0, 0);
        byte[] data = read("published-algo-v1.bin");
        assertThat(TradingSnapshotV36Reader.readPublishedAlgo(data)).isEqualTo(expected);
        assertThatThrownBy(() -> TradingSnapshotV36Reader.readPublishedAlgo(Arrays.copyOf(data, data.length - 1)))
                .isInstanceOf(ProtocolException.class);
        data[0] = 2;
        assertThatThrownBy(() -> TradingSnapshotV36Reader.readPublishedAlgo(data)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void readsPublishedTriggerWithFeesAndExplicitPriceSource() throws Exception {
        byte[] data = read("published-trigger-v3.bin");
        assertThat(TradingSnapshotV36Reader.readPublishedTrigger(data)).isEqualTo(expected(CoreTriggerPriceSource.LAST));
        assertThatThrownBy(() -> TradingSnapshotV36Reader.readPublishedTrigger(Arrays.copyOf(data, data.length - 1)))
                .isInstanceOf(ProtocolException.class);
        // The published v2 layout is v3 without its final explicit source; its defined source is MARK.
        byte[] v2 = Arrays.copyOf(data, data.length - Integer.BYTES);
        v2[0] = 2;
        assertThat(TradingSnapshotV36Reader.readPublishedTrigger(v2)).isEqualTo(expected(CoreTriggerPriceSource.MARK));
        data[0] = 4;
        assertThatThrownBy(() -> TradingSnapshotV36Reader.readPublishedTrigger(data)).isInstanceOf(ProtocolException.class);
    }

    private static CoreTriggerOrderStateView expected(CoreTriggerPriceSource source) {
        return new CoreTriggerOrderStateView(501, ProductLine.LINEAR_PERPETUAL, 1001,
                "tp-501", "oco-1", "1", CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT,
                CoreTriggerCondition.GREATER_OR_EQUAL, 70000, 0, 0, 0, 0, 0, CoreOrderType.MARKET,
                CoreTimeInForce.IOC, 0, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", "追踪", 0, 0, 1000, 1000, 1, -25, 40, source);
    }

    private static byte[] read(String file) throws Exception {
        try (var in = PublishedEmbeddedSnapshotTest.class.getResourceAsStream("/recovery/a8df45ae/" + file)) {
            if (in == null) throw new IllegalStateException("missing published fixture: " + file);
            return in.readAllBytes();
        }
    }
}
