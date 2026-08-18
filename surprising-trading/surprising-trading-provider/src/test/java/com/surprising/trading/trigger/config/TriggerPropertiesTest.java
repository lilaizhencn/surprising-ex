package com.surprising.trading.trigger.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TriggerPropertiesTest {

    @Test
    void executionConfigurationMustBePositive() {
        TriggerProperties.Execution execution = new TriggerProperties.Execution();

        assertThatThrownBy(() -> execution.setTriggerBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> execution.setMaxTriggerScanPages(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> execution.setStaleTriggeringAfter(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> execution.setMaintenanceDelayMs(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
