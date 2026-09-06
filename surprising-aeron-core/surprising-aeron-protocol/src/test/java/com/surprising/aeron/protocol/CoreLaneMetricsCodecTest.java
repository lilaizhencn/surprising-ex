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

    @Test
    void directWriterPreservesWireBytesAndSealsOwnership() {
        CoreLaneMetricsView view = view();
        var encoder = new CoreLaneMetricsCodec.Encoder(4, 2, 1, 16, 3, 2, 16, 4, 1, 16, 2, 19);
        for (int lane = 1; lane >= 0; lane--) {
            encoder.writeLane(lane, view.accountLaneRevisions()[lane], view.accountLaneAppliedSequences()[lane],
                    view.accountLaneCommittedSequences()[lane], view.accountLaneQueueDepths()[lane],
                    view.accountLaneQueueCapacities()[lane], view.accountLaneQueueHighWaterMarks()[lane],
                    view.accountLaneRejectedSubmissions()[lane], view.accountLaneOldestPendingSequences()[lane]);
            int offset = lane * CoreLaneMetricsView.OPERATION_TYPE_COUNT;
            encoder.writeOperations(lane,
                    Arrays.copyOfRange(view.accountLaneCompletedOperations(), offset, offset + 4),
                    Arrays.copyOfRange(view.accountLaneLatencySamples(), offset, offset + 4),
                    Arrays.copyOfRange(view.accountLaneTotalLatencyNanos(), offset, offset + 4),
                    Arrays.copyOfRange(view.accountLaneMaxLatencyNanos(), offset, offset + 4));
        }
        byte[] payload = encoder.finish();
        assertThat(payload).containsExactly(CoreLaneMetricsCodec.encode(view));
        assertThatThrownBy(encoder::finish).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> encoder.addOperation(0, 0, 1, 1, 1, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> encoder.writeLane(0, 0, 0, 0, 0, 16, 0, 0, 0))
                .isInstanceOf(IllegalStateException.class);
        assertThat(CoreLaneMetricsCodec.decode(payload)).usingRecursiveComparison().isEqualTo(view);
    }

    @Test
    void directWriterRequiresAllLanesAndAddsSettlementCountersExactly() {
        for (int lanes : new int[]{1, 4, 64}) {
            var encoder = new CoreLaneMetricsCodec.Encoder(1, lanes, 0, 16, 0, 0, 16, 0, 0, 16, 0, 0);
            assertThatThrownBy(encoder::finish).isInstanceOf(IllegalStateException.class);
            long[] zero = new long[4];
            for (int lane = 0; lane < lanes; lane++) {
                encoder.writeOperations(lane, zero, zero, zero, zero);
                encoder.writeLane(lane, lane, lane, lane, 0, 16, 0, 0, 0);
                encoder.addOperation(lane, 1, 3, 3, 30, 20);
                encoder.addOperation(lane, 1, 2, 2, 40, 10);
            }
            var decoded = CoreLaneMetricsCodec.decode(encoder.finish());
            for (int lane = 0; lane < lanes; lane++) {
                assertThat(decoded.accountLaneCompletedOperations()[lane * 4 + 1]).isEqualTo(5);
                assertThat(decoded.accountLaneLatencySamples()[lane * 4 + 1]).isEqualTo(5);
                assertThat(decoded.accountLaneTotalLatencyNanos()[lane * 4 + 1]).isEqualTo(70);
                assertThat(decoded.accountLaneMaxLatencyNanos()[lane * 4 + 1]).isEqualTo(20);
            }
            assertThat(zero).containsExactly(0, 0, 0, 0);
        }
    }

    @Test
    void directWriterChecksOverflowBeforeChangingCounters() {
        var encoder = new CoreLaneMetricsCodec.Encoder(1, 1, 0, 16, 0, 0, 16, 0, 0, 16, 0, 0);
        encoder.writeLane(0, 0, 0, 0, 0, 16, 0, 0, 0);
        encoder.writeOperations(0, new long[]{2, 0, 0, 0}, new long[]{Long.MAX_VALUE, 0, 0, 0},
                new long[4], new long[4]);
        assertThatThrownBy(() -> encoder.addOperation(0, 0, 1, 1, 1, 1))
                .isInstanceOf(ArithmeticException.class);
        assertThat(CoreLaneMetricsCodec.decode(encoder.finish()).accountLaneCompletedOperations()[0]).isEqualTo(2);
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
