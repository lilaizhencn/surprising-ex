package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.CommandFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
class FundsCommandIndexTest {
    @TempDir Path directory;

    @Test
    void storageFailurePoisonsFurtherReadsRatherThanTreatingHistoryAsAbsent() throws Exception {
        String property = "surprising.aeron.funds-index-directory";
        String previous = System.getProperty(property);
        Path file = Files.createFile(directory.resolve("not-a-directory"));
        System.setProperty(property, file.toString());
        try (var index = new FundsCommandIndex()) {
            assertThatThrownBy(() -> index.put(new UUID(1, 1),
                    CommandFingerprint.fromBytes(new byte[CommandFingerprint.LENGTH])))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("stop processing");
            assertThatThrownBy(() -> index.get(new UUID(2, 2)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("stop processing");
            assertThatThrownBy(index::assertHealthy).isInstanceOf(IllegalStateException.class);
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    void closeRemovesOnlyThisRuntimeIndexAndLeavesConfiguredRoot() throws Exception {
        String property = "surprising.aeron.funds-index-directory";
        String previous = System.getProperty(property);
        System.setProperty(property, directory.toString());
        Path unrelated = Files.createFile(directory.resolve("unrelated"));
        try {
            try (var index = new FundsCommandIndex()) {
                index.put(new UUID(1, 1), CommandFingerprint.fromBytes(new byte[CommandFingerprint.LENGTH]));
            }
            assertThat(Files.exists(unrelated)).isTrue();
            try (var children = Files.list(directory)) {
                assertThat(children.toList()).containsExactly(unrelated);
            }
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }
}
