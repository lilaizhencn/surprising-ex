package com.surprising.aeron.tools;

import java.time.Duration;
import java.util.Arrays;

final class P10CapacityGate {

    static final int MIN_USERS = 1_000;
    static final int MIN_SYMBOLS = 200;
    static final long MIN_RATE_PER_SECOND = 100_000;
    static final Duration MIN_DURATION = Duration.ofMinutes(40);

    private P10CapacityGate() {
    }

    static void requireConfiguration(HttpWorkloadConfig config, boolean requireJfr) {
        if (config.duration().compareTo(MIN_DURATION) < 0
                || Arrays.stream(config.users()).distinct().count() < MIN_USERS
                || Arrays.stream(config.symbols()).distinct().count() < MIN_SYMBOLS
                || config.ratePerSecond() < MIN_RATE_PER_SECOND) {
            throw new IllegalArgumentException("P10 capacity qualification requires at least 40 minutes, "
                    + "1000 users, 200 symbols and 100000 offered requests/s");
        }
        if (requireJfr && jdk.jfr.FlightRecorder.getFlightRecorder().getRecordings().stream()
                .noneMatch(recording -> recording.getState() == jdk.jfr.RecordingState.RUNNING)) {
            throw new IllegalStateException("P10 capacity qualification requires an active JFR recording");
        }
    }

    static void requireResult(HttpOpenLoopWorkload.Summary summary) {
        long terminal = Math.addExact(summary.completed(), summary.deliberatelyAborted());
        if (summary.scheduled() == 0 || summary.outstanding() != 0
                || terminal != summary.scheduled()
                || summary.completed() * 100L < summary.scheduled() * 99L
                || summary.classifications().getOrDefault(HttpOutcome.TIMEOUT, 0L) != 0
                || summary.classifications().getOrDefault(HttpOutcome.TRANSPORT_ERROR, 0L) != 0
                || summary.classifications().getOrDefault(HttpOutcome.SERVER_5XX, 0L) != 0
                || summary.classifications().getOrDefault(HttpOutcome.ORACLE_MISMATCH, 0L) != 0
                || summary.classifications().getOrDefault(HttpOutcome.RATE_LIMITED_429, 0L) != 0
                || summary.terminalRatePerSecond() < MIN_RATE_PER_SECOND) {
            throw new IllegalStateException("P10 capacity qualification failed: " + summary);
        }
    }
}
