package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CommandFingerprint;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TerminalStateRetentionTest {
    @Test
    void removingAuditDigestsPreservesFundsIdempotencyAndSnapshotBytes() {
        TerminalStateRetention retention = new TerminalStateRetention();
        UUID command = UUID.randomUUID();
        byte[] bytes = new byte[CommandFingerprint.LENGTH];
        CommandFingerprint fingerprint = CommandFingerprint.fromBytes(bytes);
        retention.retainFundsCommand(command, fingerprint);
        byte[] snapshot = retention.encode();
        retention.retainFundsCommand(command, fingerprint);
        assertThat(retention.encode()).isEqualTo(snapshot);
        assertThat(retention.copy().encode()).isEqualTo(snapshot);
        var restored = TerminalStateRetention.decode(snapshot);
        assertThat(restored.fundsCommand(command)).isEqualTo(fingerprint);
        bytes[0] = 1;
        assertThatThrownBy(() -> restored.retainFundsCommand(command, CommandFingerprint.fromBytes(bytes)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fingerprint conflict");
        assertThat(restored.encode()).isEqualTo(snapshot);
    }
}
