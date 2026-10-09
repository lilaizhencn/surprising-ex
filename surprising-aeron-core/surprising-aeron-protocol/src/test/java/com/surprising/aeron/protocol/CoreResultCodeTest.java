package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CoreResultCodeTest {
    @ParameterizedTest
    @EnumSource(CoreResultCode.class)
    void terminalRejectionsRetainTheirBusinessReasonAcrossTheWire(CoreResultCode code) {
        var response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, code, 7L, new byte[0]);
        var decoded = CoreProtocol.decodeResponse(CoreProtocol.responsePayload(response));
        assertThat(decoded.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(decoded.resultCode()).isEqualTo(code);
        assertThat(CoreResultCode.fromRejectionCode(code.name())).isEqualTo(code);
    }
}
