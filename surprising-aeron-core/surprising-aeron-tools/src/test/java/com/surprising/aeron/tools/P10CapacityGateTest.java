package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class P10CapacityGateTest {

    @Test
    void rejectsACharacterizationRunThatDoesNotMeetTheProductionGate() {
        HttpWorkloadConfig config = new HttpWorkloadConfig(URI.create("http://127.0.0.1:8080"),
                Path.of("target", "p10-capacity"), "p10-small", 1, 99_999,
                Duration.ofMinutes(39), 1_024, Duration.ofSeconds(5), Duration.ofMillis(10), 100,
                users(999), symbols(199), TrafficSkew.COMBINED_HOT, HttpWorkloadConfig.defaultTraffic());

        assertThatThrownBy(() -> P10CapacityGate.requireConfiguration(config, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("40 minutes");
    }

    @Test
    void rejectsTechnicalFailuresEvenWhenRequestAccountingBalances() {
        HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload.Summary(
                100, 100, 0, 0, 64, Map.of(HttpOutcome.TIMEOUT, 1L));

        assertThatThrownBy(() -> P10CapacityGate.requireResult(summary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qualification failed");
    }

    private static long[] users(int count) {
        long[] values = new long[count];
        Arrays.setAll(values, index -> index + 1L);
        return values;
    }

    private static String[] symbols(int count) {
        String[] values = new String[count];
        Arrays.setAll(values, index -> "P10-SYMBOL-" + index);
        return values;
    }
}
