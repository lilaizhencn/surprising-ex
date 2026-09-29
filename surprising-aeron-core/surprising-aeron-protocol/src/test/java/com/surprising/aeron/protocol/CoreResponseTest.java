package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class CoreResponseTest {
    @Test
    void triggerRejectionsKeepTheirSpecificWireCodes() {
        for (String rejection : new String[] {"TRIGGER_POSITION_REQUIRED", "TRIGGER_SIDE_NOT_REDUCING",
                "TRIGGER_CLOSE_CAPACITY_EXCEEDED", "DUPLICATE_CLIENT_TRIGGER_ORDER_ID"}) {
            CoreResultCode code = CoreResultCode.fromRejectionCode(rejection);
            assertThat(code.name()).isEqualTo(rejection);
            assertThat(CoreResultCode.fromWireCode(code.wireCode())).isEqualTo(code);
        }
    }
    @Test
    void publicConstructionAndReadsRemainDefensive() {
        byte[] input = {3, 5, 8};
        CoreResponse response = new CoreResponse(ResponseStatus.APPLIED, 7, input);
        input[0] = 99;
        response.data()[1] = 99;
        assertThat(response.data()).containsExactly(3, 5, 8);
        assertThat(response.committedCoreSequence()).isEqualTo(7);
    }

    @Test
    void ownedStorageAndDecodeKeepWireBytesAndDoNotExposeInputBuffers() {
        byte[] encodedData = {3, 5, 8};
        CoreResponse response = CoreResponse.owned(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, 7, encodedData);
        assertThat(response.dataUnsafe()).isSameAs(encodedData);
        response.data()[0] = 99;
        byte[] wire = CoreProtocol.responsePayload(response);
        CoreResponse decoded = CoreProtocol.decodeResponse(wire);
        wire[wire.length - 1] = 99;
        assertThat(decoded.data()).containsExactly(3, 5, 8);
        assertThat(CoreProtocol.responsePayload(decoded)).isEqualTo(CoreProtocol.responsePayload(response));
    }

    @Test
    void ownedSliceEncodesOnlyItsLogicalBytes() {
        byte[] storage = {99, 3, 5, 8, 99};
        CoreResponse response = CoreResponse.owned(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, 7, storage, 1, 3);
        assertThat(response.data()).containsExactly(3, 5, 8);
        CoreResponse decoded = CoreProtocol.decodeResponse(CoreProtocol.responsePayload(response));
        assertThat(decoded.data()).containsExactly(3, 5, 8);
        assertThat(response.dataLength()).isEqualTo(3);
    }
}
