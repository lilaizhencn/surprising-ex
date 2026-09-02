package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class W1W2InvariantFenceTest {

    @Test
    void failedOwnerCommitLeavesLaneForwardOnlyDoesNotPublishAndPoisonsTheInstance() throws Exception {
        try (CoreProbeState state = new CoreProbeState(
                com.surprising.product.api.ProductLine.SPOT)) {
            var journal = (com.surprising.aeron.service.state.RuntimeCommitJournal)
                    field(state, "runtimeProjectionJournal");
            var runtimeState = (com.surprising.aeron.service.state.TradingRuntimeState)
                    field(state, "runtimePlaceOrderState");
            TradingCoreRuntime runtime = (TradingCoreRuntime) field(state, "runtime");
            var activeOrders = (com.surprising.aeron.service.state.ActiveOrderIndex)
                    field(state, "activeOrderIndex");
            var laneViews = runtimeState.accountLanes();
            var activeOrderSnapshot = activeOrders.orders();
            long businessHash = (long) field(runtime, "committedBusinessStateHash");
            long revision = (long) field(runtime, "committedRevision");
            long coreSequence = runtime.committedCoreSequence();
            CoreProbeState.setCommitFaultInjectorForTest(phase -> {
                if (phase.equals("indexes")) throw new IllegalStateException("injected indexes failure");
            });
            var adjustment = new com.surprising.aeron.protocol.CoreMessage(
                    com.surprising.aeron.protocol.CoreMessageHeader.command(
                            com.surprising.aeron.protocol.CoreMessageType.ADJUST_BALANCE,
                            UUID.randomUUID(), com.surprising.product.api.ProductLine.SPOT,
                            com.surprising.aeron.protocol.CommandSource.GATEWAY, 7, 1, 1001, 1_000, 1),
                    com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(
                            new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 25)));

            assertThatThrownBy(() -> state.apply(adjustment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("injected indexes failure");

            assertThat(journal.publishedSequence()).isZero();
            assertThat(runtime.committedCoreSequence()).isEqualTo(coreSequence);
            var afterFailure = runtimeState.accountLanes();
            assertThat(afterFailure).hasSameSizeAs(laneViews);
            boolean laneAdvanced = false;
            for (int laneId = 0; laneId < afterFailure.length; laneId++) {
                assertThat(afterFailure[laneId].committedSequence())
                        .isGreaterThanOrEqualTo(laneViews[laneId].committedSequence());
                laneAdvanced |= afterFailure[laneId].committedSequence()
                        > laneViews[laneId].committedSequence();
            }
            assertThat(laneAdvanced).isTrue();
            assertThatThrownBy(() -> state.apply(adjustment))
                    .hasMessageContaining("snapshot and log is required");
        } finally {
            CoreProbeState.setCommitFaultInjectorForTest(null);
        }
    }

    @Test
    void keepsSingleBookSnapshotOnlyRestore() throws Exception {
        String adapter = source("matching/DeterministicExchangeCoreAdapter.java");
        String runtime = source("TradingCoreRuntime.java");
        String indexes = source("state/RuntimeFactIndexes.java");
        String probe = source("CoreProbeState.java");

        assertThat(adapter)
                .contains("serializationProcessor.importSnapshot(snapshot.modules())")
                .contains("InitialStateConfiguration.fromSnapshotOnly(")
                .contains("reconcileOpenOrdersAsync(activeOrders")
                .doesNotContain("fromOrders", "rebuildMatcher", "resubmitMatcher", "CoreBookState");
        assertThat(runtime).contains("private void restoreIndexes(TradingCoreState restored)");
        assertThat(runtime).contains("factIndexes.rebuild(restored, identities);");
        assertThat(linesContaining(indexes, ".rebuild(state"))
                .containsExactly(
                        "positionUsers.rebuild(state, identities);",
                        "openInterest.rebuild(state, identities);",
                        "triggers.rebuild(state);",
                        "algos.rebuild(state);",
                        "liquidations.rebuild(state);",
                        "timers.rebuild(state);",
                        "activeOrders.rebuild(state, identities);",
                        "adlPositions.rebuild(state, identities);",
                        "riskSnapshots.rebuild(state);");
        assertThat(probe)
                .contains("fatalFailure = cause == null")
                .contains("if (fatalFailure != null) throw fatalFailure;")
                .doesNotContain("CoreBookState", "LaneCommitListener", "recordLaneCommit", "cachedLane");
        assertThat(source("state/TradingRuntimeState.java"))
                .doesNotContain("LaneCommitListener", "AccountLaneApplyResult");
    }

    @Test
    void ownerCommitAppliesHashesOnceAndFailsStopWithoutRollback() throws Exception {
        String probe = source("CoreProbeState.java");
        int start = probe.indexOf("    private void projectSnapshotNow(long committedLaneMask)");
        int end = probe.indexOf("    private void reservePlaceOrderRuntime", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        String ownerCommit = probe.substring(start, end);

        assertThat(ownerCommit)
                .contains("prepareFactFrame(")
                .doesNotContain("rollingBusinessStateHash.applyFailStop")
                .doesNotContain("rollingFundsStateHash.applyFailStop")
                .contains("commitFaultInjector.inject(\"hashes\")")
                .contains("restart from snapshot and log is required")
                .contains("publishFactFrame(frame,")
                .doesNotContain("prepareApplied", "businessTransition", "fundsTransition",
                        "rollbackLaneSequence", "commitLaneSequence(")
                .doesNotContain("runtimeProjectionJournal.await")
                .doesNotContain("RuntimeStateMaterializer.materialize")
                .doesNotContain("RollingBusinessStateHash.compute")
                .doesNotContain("RollingFundsStateHash.compute")
                .doesNotContain("rollingBusinessStateHash.restore")
                .doesNotContain("rollingFundsStateHash.restore");
        assertThat(occurrences(ownerCommit, "prepareFactFrame(")).isEqualTo(1);
        assertThat(occurrences(ownerCommit, "publishFactFrame(frame,")).isEqualTo(1);
        assertThat(probe).contains("currentAdmission.publish(frame, businessStateHash, fundsStateHash)");
        assertThat(source("state/TradingRuntimeState.java"))
                .doesNotContain("LaneCommitCommand", "commitLaneSequence(")
                .contains("lane.applied(coreSequence", "lane.committed(coreSequence)");
        assertThat(source("state/RuntimeFactFrame.java"))
                .doesNotContain("Changes<Long", "Changes<Integer", "BeforeAfter", ".sort(",
                        "Arrays.sort", "TreeMap", "TreeSet", "forEachKeyValue", ".toArray(");
        assertThat(source("state/RollingBusinessStateHash.java"))
                .doesNotContain("HashTransition", "prepareApplied", "BusinessPatchStage",
                        "UserGroupUpdate", "afterAppliedOperation", "failAfterStagedOperation",
                        ".sort(", "Arrays.sort", ".toArray(", "forEachKeyValue");
        assertThat(source("state/RollingFundsStateHash.java"))
                .doesNotContain("HashTransition", "prepareApplied", "FundsPatchStage",
                        "afterAppliedOperation", "failAfterStagedOperation", ".sort(",
                        "Arrays.sort", ".toArray(", "forEachKeyValue");
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

    private static int occurrences(String source, String token) {
        return source.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }

    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
