package com.surprising.eventstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserStateChangelogReplayTest {

    @Test
    void keepsOnlyTheNewestCheckpointAndRejectsConflictingRetries() {
        UserStateChangelogReplay replay = new UserStateChangelogReplay();
        UserStateChangelog first = changelog(1L, "one");
        UserStateChangelog second = changelog(2L, "two");

        assertThat(replay.observe(first)).isEqualTo(UserStateChangelogReplay.Decision.APPLY);
        assertThat(replay.observe(first)).isEqualTo(UserStateChangelogReplay.Decision.DUPLICATE);
        assertThat(replay.observe(second)).isEqualTo(UserStateChangelogReplay.Decision.APPLY);
        assertThat(replay.observe(first)).isEqualTo(UserStateChangelogReplay.Decision.STALE);
        UserStateChangelog conflict = changelog(2L, "conflict");
        assertThatThrownBy(() -> replay.observe(conflict))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting");
    }

    private static UserStateChangelog changelog(long sequence, String state) {
        return UserStateChangelog.create(ProductLine.SPOT, 7L, sequence,
                state.getBytes(StandardCharsets.UTF_8), Instant.parse("2026-08-13T00:00:00Z"), null);
    }
}
