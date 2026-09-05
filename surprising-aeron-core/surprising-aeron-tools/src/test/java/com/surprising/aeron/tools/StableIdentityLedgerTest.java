package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StableIdentityLedgerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void identitySurvivesRetryAndRestart() throws Exception {
        Path ledgerDirectory = temporaryDirectory.resolve("ledger");
        StableIdentityLedger.Intent first;
        try (StableIdentityLedger ledger = StableIdentityLedger.open(ledgerDirectory, "resume-run", 71L)) {
            first = ledger.intent(17L, WorkloadOperation.PLACE, 9_001L, "BTC-USDT", "OPEN");
            ledger.scheduled(first, 123_456L);
            ledger.sent(first.sequence(), 123_999L);
        }

        try (StableIdentityLedger resumed = StableIdentityLedger.open(ledgerDirectory, "resume-run", 71L)) {
            StableIdentityLedger.Intent retried = resumed.intent(
                    17L, WorkloadOperation.PLACE, 9_001L, "BTC-USDT", "OPEN");
            assertThat(retried.intentId()).isEqualTo(first.intentId());
            assertThat(retried.clientIdentity()).isEqualTo(first.clientIdentity());
            assertThat(resumed.outstanding()).extracting(StableIdentityLedger.Intent::sequence).containsExactly(17L);
        }
    }

    @Test
    void abortedIntentCannotLaterBecomeCompleted() {
        Path ledgerDirectory = temporaryDirectory.resolve("exclusive-terminal-state");
        try (StableIdentityLedger ledger = StableIdentityLedger.open(
                ledgerDirectory, "exclusive-terminal-run", 70L, "config-a")) {
            StableIdentityLedger.Intent intent = ledger.intent(
                    1L, WorkloadOperation.PLACE, 9_001L, "BTC-USDT", "APPLIED");
            ledger.scheduled(intent, 10L);

            ledger.aborted(intent.sequence(), 20L, "operator interruption");
            ledger.finished(intent.sequence(), 30L, "APPLIED", "7001");

            assertThat(ledger.completedCount()).isZero();
            assertThat(ledger.abortedCount()).isEqualTo(1L);
            assertThat(ledger.scheduledCount()).isEqualTo(
                    ledger.completedCount() + ledger.outstandingCount() + ledger.abortedCount());
        }
    }

    @Test
    void rejectsCorruptionButReplaysPastAStaleCheckpoint() throws Exception {
        Path staleDirectory = temporaryDirectory.resolve("stale");
        try (StableIdentityLedger ledger = StableIdentityLedger.open(staleDirectory, "stale-run", 72L)) {
            var intent = ledger.intent(1L, WorkloadOperation.PLACE, 1L, "BTC-USDT", "OPEN");
            ledger.scheduled(intent, 10L);
            ledger.sent(intent.sequence(), 20L);
        }
        Files.writeString(staleDirectory.resolve("checkpoint.json"),
                "{\"runId\":\"stale-run\",\"seed\":72,\"eventBytes\":0,\"lastSequence\":0}\n",
                StandardCharsets.UTF_8);
        try (StableIdentityLedger resumed = StableIdentityLedger.open(staleDirectory, "stale-run", 72L)) {
            assertThat(resumed.outstanding()).hasSize(1);
        }

        Path corruptDirectory = temporaryDirectory.resolve("corrupt");
        try (StableIdentityLedger ledger = StableIdentityLedger.open(corruptDirectory, "corrupt-run", 73L)) {
            var intent = ledger.intent(1L, WorkloadOperation.PLACE, 1L, "BTC-USDT", "OPEN");
            ledger.scheduled(intent, 10L);
        }
        Files.writeString(corruptDirectory.resolve("events.jsonl"), "not-json\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> StableIdentityLedger.open(corruptDirectory, "corrupt-run", 73L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt ledger");
    }

    @Test
    void recoversAnInterruptedTrailingAppendWithoutDiscardingCommittedEvents() throws Exception {
        Path directory = temporaryDirectory.resolve("torn-tail");
        StableIdentityLedger.Intent intent;
        try (StableIdentityLedger ledger = StableIdentityLedger.open(directory, "torn-run", 74L, "config-a")) {
            intent = ledger.intent(1L, WorkloadOperation.PLACE, 7L, "BTC-USDT", "APPLIED");
            ledger.scheduled(intent, 10L);
        }
        Files.writeString(directory.resolve("events.jsonl"), "{\"event\":\"SENT\",\"sequence\":1",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        try (StableIdentityLedger resumed = StableIdentityLedger.open(directory, "torn-run", 74L, "config-a")) {
            assertThat(resumed.outstanding()).containsExactly(intent);
        }
        assertThat(Files.readString(directory.resolve("events.jsonl"))).endsWith("}\n");
    }

    @Test
    void rejectsResumeWhenTheWorkloadConfigurationChanges() throws Exception {
        Path directory = temporaryDirectory.resolve("config-mismatch");
        try (StableIdentityLedger ignored = StableIdentityLedger.open(directory, "same-run", 75L, "config-a")) {
        }
        assertThatThrownBy(() -> StableIdentityLedger.open(directory, "same-run", 75L, "config-b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configuration");
    }

    @Test
    void rejectsMalformedConfigurationAndTrafficMix() {
        Properties badRate = validProperties(temporaryDirectory.resolve("bad"));
        badRate.setProperty("rate", "0");
        assertThatThrownBy(() -> HttpWorkloadConfig.from(badRate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate");

        Properties badMix = validProperties(temporaryDirectory.resolve("bad-mix"));
        badMix.setProperty("traffic", "PLACE=90,CANCEL=9");
        assertThatThrownBy(() -> HttpWorkloadConfig.from(badMix))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    private static Properties validProperties(Path output) {
        Properties properties = new Properties();
        properties.setProperty("baseUrl", URI.create("http://127.0.0.1:1").toString());
        properties.setProperty("output", output.toString());
        properties.setProperty("runId", "config-run");
        properties.setProperty("seed", "1");
        properties.setProperty("rate", "1");
        properties.setProperty("duration", Duration.ofSeconds(1).toString());
        properties.setProperty("maxInFlight", "1");
        properties.setProperty("requestTimeout", "PT0.1S");
        properties.setProperty("pollInterval", "PT0.01S");
        properties.setProperty("maxPolls", "2");
        properties.setProperty("limitPriceTicks", "1");
        properties.setProperty("triggerPriceTicks", "1");
        properties.setProperty("users", "1");
        properties.setProperty("symbols", "BTC-USDT");
        return properties;
    }
}
