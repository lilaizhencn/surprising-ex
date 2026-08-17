package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProjectionMainTest {

    @Test
    void keepsNormalKafkaProjectionPollingFixedAt250Milliseconds() {
        assertThat(ProjectionMain.KAFKA_POLL_TIMEOUT).isEqualTo(Duration.ofMillis(250));
    }
}
