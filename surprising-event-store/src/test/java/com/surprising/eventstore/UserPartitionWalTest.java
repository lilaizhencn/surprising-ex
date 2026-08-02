package com.surprising.eventstore;

import com.surprising.product.api.ProductLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserPartitionWalTest {

    @Test
    void appendsInOrderAndReplaysAfterReopen() throws Exception {
        Path directory = Files.createTempDirectory("user-partition-wal-");
        UserPartitionKey key = new UserPartitionKey(ProductLine.SPOT, 1001L);
        try (UserPartitionWal wal = new UserPartitionWal(directory)) {
            UserPartitionEvent first = wal.append(key, "cmd-1", "BALANCE_ADJUST", bytes("one"), "fp-1", Instant.EPOCH);
            UserPartitionEvent second = wal.append(key, "cmd-2", "ORDER_RESERVE", bytes("two"), "fp-2", Instant.EPOCH.plusSeconds(1));

            assertThat(first.sequence()).isEqualTo(1L);
            assertThat(second.sequence()).isEqualTo(2L);
            assertThat(wal.lastSequence(key)).isEqualTo(2L);
            assertThat(wal.lastProjectedSequence(key)).isZero();
            wal.markProjected(key, 1L);
            assertThat(wal.lastProjectedSequence(key)).isEqualTo(1L);
            assertThat(wal.replay(key)).extracting(UserPartitionEvent::eventId)
                    .containsExactly("cmd-1", "cmd-2");
            assertThat(wal.partitions()).containsExactly(key);
            wal.markProjected(key, 2L);
            assertThat(wal.lastProjectedSequence(key)).isEqualTo(2L);
            assertThatThrownBy(() -> wal.markProjected(key, 4L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        try (UserPartitionWal reopened = new UserPartitionWal(directory)) {
            assertThat(reopened.replay(key)).extracting(UserPartitionEvent::sequence)
                    .containsExactly(1L, 2L);
        }
    }

    @Test
    void duplicateEventIsIdempotentAndConflictingPayloadIsRejected() throws Exception {
        Path directory = Files.createTempDirectory("user-partition-wal-idempotency-");
        UserPartitionKey key = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
        try (UserPartitionWal wal = new UserPartitionWal(directory)) {
            UserPartitionEvent first = wal.append(key, "cmd-1", "ORDER_RESERVE", bytes("one"), "same", Instant.EPOCH);
            UserPartitionEvent duplicate = wal.append(key, "cmd-1", "ORDER_RESERVE", bytes("different"), "same", Instant.EPOCH);

            assertThat(duplicate.sequence()).isEqualTo(first.sequence());
            assertThat(wal.replay(key)).hasSize(1);
            assertThatThrownBy(() -> wal.append(key, "cmd-1", "ORDER_RESERVE", bytes("one"), "other", Instant.EPOCH))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("different event fingerprint");
        }
    }

    @Test
    void fullSnapshotProjectionCanAdvanceAcrossSeveralEvents() throws Exception {
        Path directory = Files.createTempDirectory("user-partition-wal-snapshot-projection-");
        UserPartitionKey key = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
        try (UserPartitionWal wal = new UserPartitionWal(directory)) {
            wal.append(key, "cmd-1", "ORDER", bytes("one"), "fp-1", Instant.EPOCH);
            wal.append(key, "cmd-2", "ORDER", bytes("two"), "fp-2", Instant.EPOCH);
            wal.append(key, "cmd-3", "ORDER", bytes("three"), "fp-3", Instant.EPOCH);

            wal.markProjectedThrough(key, 3L);

            assertThat(wal.lastProjectedSequence(key)).isEqualTo(3L);
        }
    }

    @Test
    void concurrentProjectionWatermarksRemainContinuousAndIdempotent() throws Exception {
        Path directory = Files.createTempDirectory("user-partition-wal-projection-race-");
        UserPartitionKey key = new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L);
        try (UserPartitionWal wal = new UserPartitionWal(directory);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            wal.append(key, "cmd-1", "ORDER", bytes("one"), "fp-1", Instant.EPOCH);
            wal.append(key, "cmd-2", "ORDER", bytes("two"), "fp-2", Instant.EPOCH);

            var first = executor.submit(() -> wal.markProjectedThrough(key, 1L));
            var second = executor.submit(() -> wal.markProjectedThrough(key, 2L));
            first.get();
            second.get();

            assertThat(wal.lastProjectedSequence(key)).isEqualTo(2L);
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
