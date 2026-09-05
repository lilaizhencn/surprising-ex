package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CoreSettlementProgressCodecTest {
    @Test
    void preservesBlockedPageAndRejectsTruncatedPayload() {
        var progress = new CoreSettlementProgressView(11, false, true, 0, 42, 16, 0, 250);
        byte[] encoded = CoreSettlementProgressCodec.encode(progress);
        assertThat(CoreSettlementProgressCodec.decode(encoded)).isEqualTo(progress);
        assertThatThrownBy(() -> CoreSettlementProgressCodec.decode(Arrays.copyOf(encoded, encoded.length - 8)))
                .isInstanceOf(ProtocolException.class);
        encoded[8] = 1;
        assertThatThrownBy(() -> CoreSettlementProgressCodec.decode(encoded)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void rejectsNegativeInsuranceAndInconsistentCompletion() {
        assertThatThrownBy(() -> new CoreSettlementProgressView(11, false, true, 0, 42, 0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoreSettlementProgressView(11, false, false, 42, 0, 16, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoreSettlementProgressView(11, false, true, 0, 42, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
