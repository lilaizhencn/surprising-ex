package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CoreLaneMetricsCodecTest {

    @Test
    void roundTripsBoundedLaneMetrics() {
        CoreLaneMetricsView view = view();

        CoreLaneMetricsView decoded = CoreLaneMetricsCodec.decode(CoreLaneMetricsCodec.encode(view));

        assertThat(decoded).usingRecursiveComparison().isEqualTo(view);
    }

    @Test
    void rejectsTruncatedLaneMetrics() {
        byte[] encoded = CoreLaneMetricsCodec.encode(view());

        assertThatThrownBy(() -> CoreLaneMetricsCodec.decode(Arrays.copyOf(encoded, encoded.length - 1)))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("truncated");
    }

    private static CoreLaneMetricsView view() {
        return new CoreLaneMetricsView(4, 2,
                1, 16, 3, 2, 16, 4, 1, 16, 2, 19,
                new long[]{7, 8}, new long[]{19, 19}, new long[]{19, 18},
                new int[]{1, 0}, new int[]{16, 16}, new int[]{5, 4},
                new long[]{2, 0}, new long[]{100, 0},
                new long[]{3, 2, 1, 0, 4, 3, 2, 1},
                new long[]{1, 1, 1, 0, 1, 1, 1, 1},
                new long[]{30, 20, 10, 0, 40, 30, 20, 10},
                new long[]{15, 10, 10, 0, 20, 15, 10, 5});
    }
}
