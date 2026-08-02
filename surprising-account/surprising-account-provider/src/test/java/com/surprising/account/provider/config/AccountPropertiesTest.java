package com.surprising.account.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountPropertiesTest {

    @Test
    void walConfigurationMustBePositive() {
        AccountProperties.Wal wal = new AccountProperties.Wal();
        assertThatThrownBy(() -> wal.setDirectory(" "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("directory");
        assertThatThrownBy(() -> wal.setProjectionDelayMs(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("projectionDelayMs");
        assertThatThrownBy(() -> wal.setProjectionBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("projectionBatchSize");
        wal.setDirectory("data/test-account-wal");
        wal.setProjectionDelayMs(1);
        wal.setProjectionBatchSize(1);
        assertThat(wal.getDirectory()).isEqualTo("data/test-account-wal");
        assertThat(wal.getProjectionDelayMs()).isEqualTo(1);
        assertThat(wal.getProjectionBatchSize()).isEqualTo(1);
    }
}
