package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class W1W2InvariantFenceTest {

    @Test
    void keepsSingleBookSnapshotOnlyRestore() throws Exception {
        String adapter = source("matching/DeterministicExchangeCoreAdapter.java");
        String runtime = source("TradingCoreRuntime.java");
        String probe = source("CoreProbeState.java");

        assertThat(adapter)
                .contains("serializationProcessor.importSnapshot(snapshot.modules())")
                .contains("InitialStateConfiguration.fromSnapshotOnly(")
                .contains("reconcileOpenOrdersAsync(activeOrders")
                .doesNotContain("fromOrders", "rebuildMatcher", "resubmitMatcher", "CoreBookState");
        assertThat(runtime).contains("private void restoreIndexes(TradingCoreState restored)");
        assertThat(linesContaining(runtime, ".rebuild(restored"))
                .containsExactly(
                        "positionUsers.rebuild(restored, identities);",
                        "openInterest.rebuild(restored, identities);",
                        "triggers.rebuild(restored);",
                        "algos.rebuild(restored);",
                        "liquidations.rebuild(restored);",
                        "timers.rebuild(restored);",
                        "activeOrders.rebuild(restored, identities);",
                        "adlPositions.rebuild(restored, identities);",
                        "riskSnapshots.rebuild(restored);");
        assertThat(probe)
                .contains("fatalFailure = cause == null")
                .contains("if (fatalFailure != null) throw fatalFailure;")
                .doesNotContain("CoreBookState");
    }

    private static String source(String relativePath) throws Exception {
        Path testClasses = Path.of(W1W2InvariantFenceTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path module = testClasses.getParent().getParent();
        Path sourceRoot = module.resolve("src/main/java/com/surprising/aeron/service");
        return Files.readString(sourceRoot.resolve(relativePath).normalize());
    }

    private static List<String> linesContaining(String source, String needle) {
        return source.lines().map(String::trim).filter(line -> line.contains(needle)).toList();
    }
}
