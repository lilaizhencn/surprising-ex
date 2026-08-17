package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.aeron.driver.ThreadingMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SurprisingClusterNodeThreadingModeTest {

    private static final String PROPERTY = "surprising.aeron.core.threading-mode";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void acceptsConfiguredThreadingMode() {
        System.setProperty(PROPERTY, "DEDICATED");

        assertThat(SurprisingClusterNode.coreThreadingMode()).isEqualTo(ThreadingMode.DEDICATED);
    }

    @Test
    void rejectsUnknownThreadingMode() {
        System.setProperty(PROPERTY, "invalid");

        assertThatIllegalArgumentException().isThrownBy(SurprisingClusterNode::coreThreadingMode)
                .withMessageContaining("valid Aeron ThreadingMode");
    }
}
