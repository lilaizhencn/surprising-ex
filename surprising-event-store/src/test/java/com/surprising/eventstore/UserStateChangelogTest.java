package com.surprising.eventstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserStateChangelogTest {

    @Test
    void createsPartitionedChecksummedCheckpointWithDefensiveState() {
        byte[] state = "state".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UserStateChangelog changelog = UserStateChangelog.create(ProductLine.SPOT, 7L, 3L, state,
                Instant.parse("2026-08-13T00:00:00Z"), "trace");
        state[0] = 'X';

        assertThat(changelog.partitionKey()).isEqualTo("SPOT:7");
        assertThat(changelog.userPartition()).isEqualTo(new UserPartitionKey(ProductLine.SPOT, 7L));
        assertThat(changelog.sequence()).isEqualTo(3L);
        assertThat(changelog.state()).isEqualTo("state".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(changelog.stateChecksum()).hasSize(64);
    }

    @Test
    void rejectsTamperedCheckpointState() {
        assertThatThrownBy(() -> new UserStateChangelog(1, ProductLine.SPOT, 7L, 3L,
                new byte[]{1}, "00", Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");
    }
}
