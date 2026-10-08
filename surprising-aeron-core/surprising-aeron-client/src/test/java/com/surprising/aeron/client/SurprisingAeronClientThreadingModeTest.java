package com.surprising.aeron.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.aeron.driver.ThreadingMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SurprisingAeronClientThreadingModeTest {

    private static final String PROPERTY = "surprising.aeron.client.threading-mode";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void defaultsToSharedDriverUnlessEnvironmentOverridesIt() {
        String configured = System.getenv("AERON_CLIENT_THREADING_MODE");
        assertThat(SurprisingAeronClient.clientThreadingMode()).isEqualTo(configured == null
                ? ThreadingMode.SHARED
                : ThreadingMode.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    void acceptsSharedThreadingMode() {
        System.setProperty(PROPERTY, "shared");
        assertThat(SurprisingAeronClient.clientThreadingMode()).isEqualTo(ThreadingMode.SHARED);
    }

    @Test
    void acceptsConfiguredThreadingMode() {
        System.setProperty(PROPERTY, "DEDICATED");

        assertThat(SurprisingAeronClient.clientThreadingMode()).isEqualTo(ThreadingMode.DEDICATED);
    }

    @Test
    void rejectsUnknownThreadingMode() {
        System.setProperty(PROPERTY, "invalid");

        assertThatIllegalArgumentException().isThrownBy(SurprisingAeronClient::clientThreadingMode)
                .withMessageContaining("valid Aeron ThreadingMode");
    }
}
