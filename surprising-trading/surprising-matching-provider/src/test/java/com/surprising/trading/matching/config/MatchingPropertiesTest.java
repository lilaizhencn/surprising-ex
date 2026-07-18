package com.surprising.trading.matching.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchingPropertiesTest {

    @Test
    void outboxCleanupConfigurationMustBePositive() {
        MatchingProperties.Outbox outbox = new MatchingProperties.Outbox();

        assertThatThrownBy(() -> outbox.setRetention(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retention");
        assertThatThrownBy(() -> outbox.setRetention(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retention");
        assertThatThrownBy(() -> outbox.setRetention(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retention");
        assertThatThrownBy(() -> outbox.setCleanupDelayMs(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cleanupDelayMs");
        assertThatThrownBy(() -> outbox.setCleanupBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cleanupBatchSize");
        assertThatThrownBy(() -> outbox.setCleanupMaxBatches(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cleanupMaxBatches");

        outbox.setRetention(Duration.ofSeconds(1));
        outbox.setCleanupDelayMs(1);
        outbox.setCleanupBatchSize(1);
        outbox.setCleanupMaxBatches(1);
        assertThat(outbox.getRetention()).isEqualTo(Duration.ofSeconds(1));
        assertThat(outbox.getCleanupDelayMs()).isEqualTo(1);
        assertThat(outbox.getCleanupBatchSize()).isEqualTo(1);
        assertThat(outbox.getCleanupMaxBatches()).isEqualTo(1);
    }

    @Test
    void internalMarketMakerWhitelistMustContainDistinctPositiveUserIds() {
        MatchingProperties.Protection protection = new MatchingProperties.Protection();

        assertThatThrownBy(() -> protection.setInternalMarketMakerUserIds(List.of(900001L, 900001L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> protection.setInternalMarketMakerUserIds(List.of(0L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        protection.setInternalMarketMakerUserIds(List.of(900001L, 900002L));
        assertThat(protection.isInternalMarketMaker(900001L)).isTrue();
        assertThat(protection.isInternalMarketMaker(1001L)).isFalse();
    }
}
