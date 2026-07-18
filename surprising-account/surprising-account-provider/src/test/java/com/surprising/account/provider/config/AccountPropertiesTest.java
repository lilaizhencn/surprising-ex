package com.surprising.account.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AccountPropertiesTest {

    @Test
    void outboxCleanupConfigurationMustBePositive() {
        AccountProperties.Outbox outbox = new AccountProperties.Outbox();

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
}
