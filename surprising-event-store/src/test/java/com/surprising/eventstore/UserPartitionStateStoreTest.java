package com.surprising.eventstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UserPartitionStateStoreTest {

    @Test
    void stateAndSequenceRecoverTogetherAndRejectGaps() throws Exception {
        Path directory = Files.createTempDirectory("user-partition-state-");
        UserPartitionKey partition = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
        try (UserPartitionStateStore store = new UserPartitionStateStore(directory)) {
            store.initialize(partition, bytes("zero"));
            store.initialize(partition, bytes("zero"));
            assertThat(store.lastAppliedSequence(partition)).isZero();
            assertThatThrownBy(() -> store.initialize(partition, bytes("other")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("初始化快照冲突");
            store.apply(partition, 1L, bytes("one"));
            store.apply(partition, 1L, bytes("one"));
            assertThat(store.read(partition).orElseThrow().state())
                    .isEqualTo(bytes("one"));
            assertThatThrownBy(() -> store.apply(partition, 3L, bytes("three")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("continuous");
            assertThatThrownBy(() -> store.apply(partition, 1L, bytes("conflict")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("conflicting");
        }
        try (UserPartitionStateStore reopened = new UserPartitionStateStore(directory)) {
            UserPartitionStateStore.StateSnapshot snapshot = reopened.read(partition).orElseThrow();
            assertThat(snapshot.sequence()).isEqualTo(1L);
            assertThat(snapshot.state()).isEqualTo(bytes("one"));
            assertThat(reopened.partitions()).containsExactly(partition);
        }
    }

    @Test
    void newerBroadcastSnapshotCanReplaceOnlyAnUnappliedBaseline() throws Exception {
        Path directory = Files.createTempDirectory("user-partition-state-replace-");
        UserPartitionKey partition = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
        try (UserPartitionStateStore store = new UserPartitionStateStore(directory)) {
            store.initialize(partition, bytes("old"));
            store.replaceIfUnapplied(partition, bytes("new"));
            assertThat(store.read(partition).orElseThrow().state()).isEqualTo(bytes("new"));
            store.apply(partition, 1L, bytes("applied"));
            assertThatThrownBy(() -> store.replaceIfUnapplied(partition, bytes("stale")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已经应用事实");
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
