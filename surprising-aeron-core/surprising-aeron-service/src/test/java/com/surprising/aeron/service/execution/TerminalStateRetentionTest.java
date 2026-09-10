package com.surprising.aeron.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CommandFingerprint;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TerminalStateRetentionTest {
    @Test
    void reusingLookupKeysCannotMutateStoredKeysOrSnapshot() {
        var retention = new TerminalStateRetention();
        retention.accept(new com.surprising.aeron.service.state.OrderRuntime(1, 7, 0, 1, true), 1);
        byte[] snapshot = retention.encode();
        for (int id = 2; id < 1000; id++) {
            assertThat(retention.containsOrder(id, 8, "client-" + id)).isFalse();
            assertThat(retention.containsOrder(1, 7, "")).isTrue();
            assertThat(retention.containsAlgo(id, 9, "algo-" + id)).isFalse();
        }
        assertThat(retention.encode()).isEqualTo(snapshot);
        assertThat(TerminalStateRetention.decode(snapshot).containsOrder(1, 7, "")).isTrue();
    }

    @Test
    void utf8ClientLimitIsUnchangedWithoutEncodingATemporaryArray() {
        var retention = new TerminalStateRetention();
        for (String client : new String[]{"x".repeat(256), "😀".repeat(64), "中".repeat(85), "\ud800".repeat(256)}) {
            assertThat(retention.containsOrder(1, 7, client)).isFalse();
        }
        for (String client : new String[]{"x".repeat(257), "😀".repeat(65), "中".repeat(86)}) {
            assertThatThrownBy(() -> retention.containsOrder(1, 7, client)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void oneSequenceEvictsEveryExcessTombstoneAndKeepsTheNewestAfterRestore() {
        var retention = new TerminalStateRetention();
        for (long id = 1; id <= TerminalStateRetention.MAX_TOMBSTONES + 40; id++)
            retention.accept(new com.surprising.aeron.service.state.OrderRuntime(id, 7, 0, 1, true), 1);
        retention.completeSequence();
        assertThat(retention.tombstoneCount()).isEqualTo(TerminalStateRetention.MAX_TOMBSTONES);
        for (var state : new TerminalStateRetention[]{retention, TerminalStateRetention.decode(retention.encode())}) {
            assertThat(state.containsOrder(40, 7, "")).isFalse();
            assertThat(state.containsOrder(41, 7, "")).isTrue();
            assertThat(state.containsOrder(TerminalStateRetention.MAX_TOMBSTONES + 40, 7, "")).isTrue();
        }
    }
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
