package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreStateSnapshotCodecTest {

    @Test
    void snapshotRestoresFingerprintResponseBytesAndRetentionMetadata() {
        UUID commandId = UUID.randomUUID();
        byte[] response = {1, 3, 5, 7, 11};
        CommandFingerprint fingerprint = CommandFingerprint.fromBytes(new byte[] {
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31
        });
        CoreProbeState.StoredResult stored = new CoreProbeState.StoredResult(
                fingerprint, ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 17, 77, response, 4);
        CoreProbeState original = CoreProbeState.restore(ProductLine.SPOT, 1, 0,
                Map.of(commandId, stored), Map.of(),
                com.surprising.aeron.service.state.TradingCoreState.empty(ProductLine.SPOT),
                new CoreExportState());

        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, original.snapshot());

        assertThat(restored.commandResults().get(commandId).responseData()).containsExactly(response);
        assertThat(restored.commandResults().get(commandId).fingerprint()).isEqualTo(fingerprint);
        assertThat(restored.commandResults().get(commandId).appliedCommandCount()).isEqualTo(1);
        assertThat(restored.commandResults().get(commandId).requiredExportSequence()).isEqualTo(17);
        assertThat(restored.commandResults().get(commandId).stateHash()).isEqualTo(77);
        assertThat(restored.commandResults().get(commandId).retentionSequence()).isEqualTo(4);
    }
}
