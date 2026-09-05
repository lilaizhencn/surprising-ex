package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreLiquidationWorkCodecTest {

    @Test
    void roundTripsBoundedLiquidationWork() {
        var query = new CoreLiquidationWorkView.Query(ProductLine.LINEAR_PERPETUAL,
                CoreLiquidationWorkView.Purpose.EXECUTION, 41, 128, 65_536);
        assertThat(CoreLiquidationWorkCodec.decodeQuery(CoreLiquidationWorkCodec.encodeQuery(query)))
                .isEqualTo(query);
        CoreLiquidationWorkView work = new CoreLiquidationWorkView(ProductLine.LINEAR_PERPETUAL,
                7, true, new CoreRiskScanContinuation("BTC-USDT", 19, 41), List.of(
                new CoreLiquidationActionView(7, 11, "BTC-USDT", CoreMarginMode.ISOLATED,
                        CorePositionSide.LONG, 3, 19, 5, 5, 60_000, "ORDERED", 91)), List.of());

        assertThat(CoreLiquidationWorkCodec.decodeWork(CoreLiquidationWorkCodec.encodeWork(work))).isEqualTo(work);
    }

    @Test
    void rejectsTruncatedAndTrailingWorkPayloads() {
        CoreLiquidationWorkView work = new CoreLiquidationWorkView(ProductLine.LINEAR_PERPETUAL,
                7, true, null, List.of(
                new CoreLiquidationActionView(7, 11, "BTC-USDT", CoreMarginMode.ISOLATED,
                        CorePositionSide.LONG, 3, 19, 5, 5, 60_000, "PLANNED", 0)), List.of());
        byte[] encoded = CoreLiquidationWorkCodec.encodeWork(work);

        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThatThrownBy(() -> CoreLiquidationWorkCodec.decodeWork(truncated))
                    .isInstanceOf(ProtocolException.class);
        }
        assertThatThrownBy(() -> CoreLiquidationWorkCodec.decodeWork(Arrays.copyOf(encoded, encoded.length + 1)))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void roundTripsInitialAndContinuingLiquidationCursors() {
        CoreLiquidationProgressView initial = new CoreLiquidationProgressView(false, 0, 0);
        CoreLiquidationProgressView continuing = new CoreLiquidationProgressView(false, 91, 37);

        assertThat(CoreLiquidationProgressCodec.decode(CoreLiquidationProgressCodec.encode(initial)))
                .isEqualTo(initial);
        assertThat(CoreLiquidationProgressCodec.decode(CoreLiquidationProgressCodec.encode(continuing)))
                .isEqualTo(continuing);
    }

    @Test
    void roundTripsDeterministicInsuranceRecommendation() {
        var resolution = new CoreLiquidationWorkView.Resolution(
                7, 11, "BTC-USDT", "USDT", CoreMarginMode.ISOLATED, CorePositionSide.LONG,
                3, 19, 5, 100, 37, CoreLiquidationWorkView.Purpose.INSURANCE);
        var work = new CoreLiquidationWorkView(ProductLine.LINEAR_PERPETUAL,
                7, true, null, List.of(), List.of(resolution));

        assertThat(CoreLiquidationWorkCodec.decodeWork(CoreLiquidationWorkCodec.encodeWork(work)))
                .isEqualTo(work);
    }
}
