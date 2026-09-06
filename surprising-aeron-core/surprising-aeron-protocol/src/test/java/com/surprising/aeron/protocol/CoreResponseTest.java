package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class CoreResponseTest {
    @Test
    void publicConstructionAndReadsRemainDefensive() {
        byte[] input = {3, 5, 8};
        CoreResponse response = new CoreResponse(ResponseStatus.APPLIED, 7, 11, input);
        input[0] = 99;
        response.data()[1] = 99;
        assertThat(response.data()).containsExactly(3, 5, 8);
        CoreResponse stamped = response.withCommittedCoreSequence(17);
        assertThat(stamped.dataUnsafe()).isSameAs(response.dataUnsafe());
        stamped.data()[2] = 99;
        assertThat(stamped.data()).containsExactly(3, 5, 8);
        assertThat(response.committedCoreSequence()).isEqualTo(7);
        assertThat(stamped.committedCoreSequence()).isEqualTo(17);
    }

    @Test
    void ownedStorageAndDecodeKeepWireBytesAndDoNotExposeInputBuffers() {
        byte[] encodedData = {3, 5, 8};
        CoreResponse response = CoreResponse.owned(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, 7, 9, 11, encodedData);
        assertThat(response.dataUnsafe()).isSameAs(encodedData);
        response.data()[0] = 99;
        byte[] wire = CoreProtocol.responsePayload(response);
        CoreResponse decoded = CoreProtocol.decodeResponse(wire);
        wire[wire.length - 1] = 99;
        assertThat(decoded.data()).containsExactly(3, 5, 8);
        assertThat(CoreProtocol.responsePayload(decoded)).isEqualTo(CoreProtocol.responsePayload(response));
        assertThatThrownBy(() -> response.withCommittedCoreSequence(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
