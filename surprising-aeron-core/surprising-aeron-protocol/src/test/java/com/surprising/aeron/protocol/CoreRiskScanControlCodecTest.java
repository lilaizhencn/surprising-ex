package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CoreRiskScanControlCodecTest {

    @Test
    void roundTripsCommandAndView() {
        var command = new UpdateRiskScanControlCommand(7, "Production scan", false,
                250, 384, "risk-admin", "incident mitigation");
        var view = new CoreRiskScanControlView(8, "Production scan", false,
                250, 384, "risk-admin", "incident mitigation", 1_700_000_000_000L);

        assertThat(CoreRiskScanControlCodec.decodeCommand(CoreRiskScanControlCodec.encodeCommand(command)))
                .isEqualTo(command);
        assertThat(CoreRiskScanControlCodec.decodeView(CoreRiskScanControlCodec.encodeView(view)))
                .isEqualTo(view);
    }

    @Test
    void rejectsUnsupportedTruncatedAndTrailingPayloads() {
        byte[] encoded = CoreRiskScanControlCodec.encodeCommand(new UpdateRiskScanControlCommand(
                1, "scan", true, 1_000, 500, "admin", "change"));
        byte[] unsupported = encoded.clone();
        unsupported[0] = 2;

        assertThatThrownBy(() -> CoreRiskScanControlCodec.decodeCommand(unsupported))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreRiskScanControlCodec.decodeCommand(
                Arrays.copyOf(encoded, encoded.length - 1))).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreRiskScanControlCodec.decodeCommand(
                Arrays.copyOf(encoded, encoded.length + 1))).isInstanceOf(ProtocolException.class);
    }
}
