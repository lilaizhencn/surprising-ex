package com.surprising.eventstore;

import com.surprising.product.api.ProductLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
            assertThat(wal.replay(key)).extracting(UserPartitionEvent::eventId)
                    .containsExactly("cmd-1", "cmd-2");
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

    private byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
