package com.surprising.risk.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskPropertiesTest {

    @Test
    void validatesAeronClusterBoundary() {
        RiskProperties.Aeron aeron = new RiskProperties.Aeron();
        assertThatThrownBy(() -> aeron.setHostnames(List.of("one", "two")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> aeron.setHostnames(List.of("one", " ", "three")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> aeron.setResponseTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> aeron.setClientConnections(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> aeron.setClientConnections(65))
                .isInstanceOf(IllegalArgumentException.class);

        aeron.setHostnames(List.of("one", "two", "three"));
        aeron.setResponseTimeout(Duration.ofSeconds(1));
        aeron.setClientConnections(64);
        assertThat(aeron.getHostnames()).containsExactly("one", "two", "three");
    }
}
