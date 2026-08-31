package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.product.api.ProductLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TradingCoreRuntimeAuthorityTest {

    @Test
    void materializesCompatibilityStateFromAuthoritativeRuntime() {
        try (TradingCoreRuntime runtime = new TradingCoreRuntime(
                ProductLine.SPOT, TradingCoreState.empty(ProductLine.SPOT))) {
            runtime.runtimeStateForConstruction().setMetadata(ProductLine.SPOT, 7);

            assertThat(runtime.snapshotState().revision()).isEqualTo(7);
        }
    }

    @Test
    void protectsAuthoritativeRuntimeFromNonOwnerThreads() throws InterruptedException {
        try (TradingCoreRuntime runtime = new TradingCoreRuntime(
                ProductLine.SPOT, TradingCoreState.empty(ProductLine.SPOT))) {
            runtime.bindOwner();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    runtime.runtimeStateForConstruction();
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            other.start();
            other.join();

            assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> {
                if (failure.get() == null) throw new AssertionError("missing owner-thread rejection");
                throw failure.get();
            }).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void doesNotExposeImmutableOutcomeToRuntimeMutationAdapters() {
        assertThatThrownBy(() -> Class.forName(
                "com.surprising.aeron.service.state.RuntimeStateDeltaApplier"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(
                "com.surprising.aeron.service.state.RuntimePlaceOrderDeltaApplier"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(
                "com.surprising.aeron.service.state.RuntimeCancelOrderDeltaApplier"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void forbidsLegacyCommitAndMaterializationPaths() throws Exception {
        String production = productionSources();
        assertThat(deletedTokens(production)).isEmpty();

        String probe = source("CoreProbeState.java");
        int start = probe.indexOf("    private void projectSnapshotNow(\n            List<");
        int end = probe.indexOf("    private void publishSealedCommit(", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        String ownerCommit = probe.substring(start, end);
        assertThat(ownerCommit).contains("preparedCommit.builder().prepare(", "preparedCommit.seal(preparedChanges",
                        "runtime.commitRuntimeTransition(commit", "publishSealedCommit(commit,")
                .doesNotContain("RuntimeStateMaterializer.materialize", "RollingBusinessStateHash.compute",
                        "RollingFundsStateHash.compute", "StateMapSupport", ".await(");
        assertThat(occurrences(ownerCommit, "preparedCommit.builder().prepare(")).isEqualTo(1);
        assertThat(occurrences(ownerCommit, "preparedCommit.seal(preparedChanges")).isEqualTo(1);
        assertThat(occurrences(ownerCommit, "runtime.commitRuntimeTransition(commit")).isEqualTo(1);
        assertThat(occurrences(ownerCommit, "publishSealedCommit(commit,")).isEqualTo(1);
        assertThat(probe).contains("currentAdmission.publish(commit, businessStateHash, fundsStateHash)");
        assertThat(occurrences(production, "commitIndexes.apply(entry)")).isEqualTo(1);
        assertThat(occurrences(production,
                "currentAdmission.publish(commit, businessStateHash, fundsStateHash)")).isEqualTo(1);
        assertThat(occurrences(production, "publishSealedCommit(")).isEqualTo(2);
        assertThat(source("state/RuntimeCommitPatch.java"))
                .doesNotContain("orderSnapshot", "captureCommitPatch", "RuntimePatchValues");
        String runtimeState = source("state/TradingRuntimeState.java");
        String prepareCommit = method(runtimeState, "    public PreparedCommit prepareCommitPatch(");
        assertThat(runtimeState)
                .doesNotContain("captureCommitPatch", "public RuntimeCommitPatch seal()", ".seal();");
        assertThat(occurrences(production, ".recordOrder(")).isEqualTo(1);
        assertThat(occurrences(prepareCommit, "RuntimeStateMaterializer.orderSnapshot(")).isEqualTo(2);
        assertThat(occurrences(prepareCommit, "RuntimeCommitPatch.exportOrderView(")).isZero();
        assertThat(occurrences(production, "exportOrderView(")).isEqualTo(1);
        assertThat(source("state/RuntimeCommitPatch.java"))
                .contains("CoreOrderState businessBefore, CoreOrderState businessAfter)",
                        "typed order patch values are required");
        assertNoOrderSnapshotFallback(
                source("state/RuntimeCommitJournal.java"),
                source("state/RuntimeCommitIndexes.java"),
                source("state/RuntimeProjectionState.java"),
                source("CoreExportState.java"),
                source("TerminalStateRetention.java"),
                protocolSource("CoreExportCodec.java"));
        assertThat(placeholderZeroHashSeals(production)).isEmpty();
        assertThat(source("state/RuntimeCommitIndexes.java"))
                .doesNotContain("update(TradingCoreState before, TradingCoreState after)");
        assertThat(source("CoreExportState.java"))
                .contains("new SpscTaskQueue<>(eventCapacity)",
                        "private final AtomicReferenceArray<E> slots")
                .doesNotContain("newSingleThreadExecutor", "ArrayBlockingQueue", "LinkedBlockingQueue",
                        "LinkedTransferQueue", "CompletableFuture<MaterializedExport>");
        assertThat(occurrences(production, "RuntimeStateMaterializer.materialize(")).isEqualTo(1);
        assertThat(method(source("TradingCoreRuntime.java"), "    public TradingCoreState snapshotState()"))
                .contains("RuntimeStateMaterializer.materialize(runtimeState, identities)");
    }

    @Test
    void forbidsNonSectionedSnapshotRecoveryPaths() throws Exception {
        String codec = source("CoreStateSnapshotCodec.java");
        assertThat(codec)
                .contains("return SectionedCoreSnapshotCodec.manifest(snapshot, expectedProductLine);",
                        "return SectionedCoreSnapshotCodec.decode(snapshot, expectedProductLine);")
                .doesNotContain("VERSION == 0", "legacy core snapshot", "CoreExportState",
                        "CoreMessageCodec.decode", "ByteBuffer.wrap", "CoreProbeState.restore");

        String probe = source("CoreProbeState.java");
        assertThat(probe).doesNotContain(
                "static CoreProbeState restore(", "restoreInternal", "activateImmediately");

        assertThat(forbiddenSnapshotRecoveryTokens(
                "if (VERSION == 0) throw failure; new CoreExportState(); CoreProbeState.restore(a); "
                        + "static CoreProbeState restore() {} restoreInternal(); activateImmediately = true;"))
                .containsExactly("VERSION == 0", "new CoreExportState", "CoreProbeState.restore",
                        "static CoreProbeState restore(", "restoreInternal", "activateImmediately");
    }

    @Test
    void detectsEveryForbiddenSinglePathFixture() {
        List<ForbiddenFixture> fixtures = List.of(
                new ForbiddenFixture("deleted mutation delta", "RuntimeMutationDelta value;"),
                new ForbiddenFixture("deleted ledger", "RuntimeCommitLedger value;"),
                new ForbiddenFixture("deleted entry", "RuntimeCommitEntry value;"),
                new ForbiddenFixture("deleted projection journal", "RuntimeProjectionJournal value;"),
                new ForbiddenFixture("deleted recorder", "RuntimeMutationRecorder value;"),
                new ForbiddenFixture("patch values shadow", "RuntimePatchValues value;"),
                new ForbiddenFixture("transition materialization", "materializeTransition();"),
                new ForbiddenFixture("persistent tree hot path", "StateMapSupport.delta(values);"),
                new ForbiddenFixture("legacy index update", "void update(TradingCoreState before, TradingCoreState after) {}"),
                new ForbiddenFixture("Core Fact projection await", "appendCoreFact() { journal.await(point); }"),
                new ForbiddenFixture("order snapshot fallback", "RuntimeStateMaterializer.orderSnapshot(order, ids);"),
                new ForbiddenFixture("unbounded executor", "Executors.newSingleThreadExecutor();"),
                new ForbiddenFixture("unbounded queue", "new LinkedBlockingQueue<>();"),
                new ForbiddenFixture("no hash seal", "prepared.seal();"),
                new ForbiddenFixture("placeholder zero hash seal", "prepared.seal(changes, 0, 0);"),
                new ForbiddenFixture("owner full materialize", "projectSnapshotNow() { RuntimeStateMaterializer.materialize(a, b); }"),
                new ForbiddenFixture("owner full hash compute", "projectSnapshotNow() { RollingFundsStateHash.compute(state); }"));

        for (ForbiddenFixture fixture : fixtures) {
            assertThat(forbiddenTokens(fixture.source())).as(fixture.name()).isNotEmpty();
        }
    }

    @Test
    void permitsSnapshotAndJournalAwaitOnlyAtExplicitFences() throws Exception {
        String production = productionSources();
        assertThat(linesContaining(production, ".snapshotState()")).isEmpty();
        assertThat(linesContaining(production, ".awaitState(")).isEmpty();

        String probe = source("CoreProbeState.java");
        String snapshotFence = method(probe, "    SectionedCoreSnapshotCodec.SectionedSnapshot pollSnapshotSections(");
        String queryFence = method(probe, "    public TradingCoreState tradingState()");
        String restoreFence = method(probe, "    private void restoreCommandState(RuntimeProjectionPoint projectionPoint)");
        String orderBatch = section(probe, "    private CoreResponse activateOrderBatch(",
                "    private void recordSourceSequence(");
        assertThat(occurrences(probe, "runtimeProjectionJournal.await(")).isEqualTo(3);
        assertThat(occurrences(snapshotFence, "runtimeProjectionJournal.await(")).isEqualTo(1);
        assertThat(occurrences(queryFence, "runtimeProjectionJournal.await(")).isEqualTo(1);
        assertThat(occurrences(restoreFence, "runtimeProjectionJournal.await(")).isEqualTo(1);
        assertThat(journalAwaitOutsideFence(probe)).isEmpty();
        assertThat(orderBatch).doesNotContain("runtimeProjectionJournal.await(", "snapshotState",
                        "TradingCoreState", "RuntimeStateMaterializer.materialize", "restoreCommandState(")
                .contains("runtimePlaceOrderState.commandCheckpoint()", "currentPatchOrderBefore(",
                        "currentPatchPositionBefore(", "failOrderBatch(", "rollbackOrderBatchMutations(");
        assertThat(method(probe, "    private void rollbackOrderBatchMutations("))
                .contains("rollbackActiveCommand(batch.runtimeCheckpoint", "batch.rollbackPreparedClientKeys(")
                .doesNotContain("restoreCommandState(");
        assertThat(method(probe, "    private com.surprising.aeron.service.matching.FatalMatchingDivergenceException failOrderBatch("))
                .contains("rollbackOrderBatchMutations(batch, true)");
        assertThat(section(probe, "    private static final class OrderBatchPending {",
                "    private static final class PipelinedBatchNotApplicable"))
                .doesNotContain("snapshotState", "TradingCoreState", "runtimeProjectionJournal.await",
                        "RuntimeStateMaterializer.materialize");
    }

    @Test
    void rejectsAnExtraJournalAwaitOutsideTheFenceAllowlist() {
        String unallowedOwnerCommit = """
                private void projectSnapshotNow() {
                    runtimeProjectionJournal.await(point);
                }
                """;
        assertThat(journalAwaitOutsideFence(unallowedOwnerCommit))
                .containsExactly("runtimeProjectionJournal.await(point);");
    }

    private static List<String> forbiddenTokens(String source) {
        List<String> forbidden = new java.util.ArrayList<>(List.of(
                "RuntimeMutationDelta", "RuntimeCommitLedger", "RuntimeCommitEntry", "RuntimeProjectionJournal",
                        "RuntimePatchValues",
                        "RuntimeMutationRecorder", "RuntimeCommitRecorder", "materializeTransition",
                        "StateMapSupport", "update(TradingCoreState before, TradingCoreState after)",
                        "Executors.new", "newSingleThreadExecutor", "LinkedBlockingQueue", "prepared.seal();",
                        "RuntimeStateMaterializer.materialize", "RollingFundsStateHash.compute", ".await(")
                .stream().filter(source::contains).toList());
        if (source.contains("orderSnapshot")) forbidden.add("order snapshot fallback");
        if (!placeholderZeroHashSeals(source).isEmpty()) forbidden.add("placeholder zero hash seal");
        return List.copyOf(forbidden);
    }

    private static List<String> placeholderZeroHashSeals(String source) {
        return source.lines()
                .filter(line -> line.matches(".*\\.seal\\([^;]*,\\s*0\\s*,\\s*0\\s*\\).*"))
                .toList();
    }

    private static List<String> deletedTokens(String source) {
        return List.of("RuntimeMutationDelta", "RuntimeCommitLedger", "RuntimeCommitEntry", "RuntimeProjectionJournal",
                        "RuntimePatchValues",
                        "RuntimeMutationRecorder", "RuntimeCommitRecorder", "materializeTransition")
                .stream().filter(source::contains).toList();
    }

    private static List<String> forbiddenSnapshotRecoveryTokens(String source) {
        return List.of("VERSION == 0", "new CoreExportState", "CoreProbeState.restore",
                        "static CoreProbeState restore(", "restoreInternal", "activateImmediately")
                .stream().filter(source::contains).toList();
    }

    private static int occurrences(String source, String token) {
        return source.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }

    private static List<String> linesContaining(String source, String token) {
        return source.lines().filter(line -> line.contains(token)).toList();
    }

    private static List<String> journalAwaitOutsideFence(String source) {
        String withoutAllowedFences = source;
        for (String signature : List.of(
                "    SectionedCoreSnapshotCodec.SectionedSnapshot pollSnapshotSections(",
                "    public TradingCoreState tradingState()",
                "    private void restoreCommandState(RuntimeProjectionPoint projectionPoint)")) {
            withoutAllowedFences = removeMethodIfPresent(withoutAllowedFences, signature);
        }
        return linesContaining(withoutAllowedFences, "runtimeProjectionJournal.await(")
                .stream().map(String::trim).toList();
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).as("method signature %s", signature).isGreaterThanOrEqualTo(0);
        int privateEnd = source.indexOf("\n    private ", start + signature.length());
        int publicEnd = source.indexOf("\n    public ", start + signature.length());
        int end = privateEnd < 0 ? publicEnd : publicEnd < 0 ? privateEnd : Math.min(privateEnd, publicEnd);
        return source.substring(start, end < 0 ? source.length() : end);
    }

    private static String section(String source, String startSignature, String endSignature) {
        int start = source.indexOf(startSignature);
        int end = source.indexOf(endSignature, start);
        assertThat(start).as("section start %s", startSignature).isGreaterThanOrEqualTo(0);
        assertThat(end).as("section end %s", endSignature).isGreaterThan(start);
        return source.substring(start, end);
    }

    private static String removeMethodIfPresent(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) return source;
        int privateEnd = source.indexOf("\n    private ", start + signature.length());
        int publicEnd = source.indexOf("\n    public ", start + signature.length());
        int packageEnd = source.indexOf("\n    SectionedCoreSnapshotCodec.", start + signature.length());
        int end = firstPositive(privateEnd, publicEnd, packageEnd);
        return source.substring(0, start) + source.substring(end < 0 ? source.length() : end);
    }

    private static int firstPositive(int... candidates) {
        int earliest = -1;
        for (int candidate : candidates) {
            if (candidate >= 0 && (earliest < 0 || candidate < earliest)) earliest = candidate;
        }
        return earliest;
    }

    private static void assertNoOrderSnapshotFallback(String... consumers) {
        for (String consumer : consumers) {
            assertThat(consumer).doesNotContain("orderSnapshot", "exportOrderView(");
        }
    }

    private static String productionSources() throws Exception {
        try (var paths = Files.walk(sourceRoot())) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted()
                    .map(TradingCoreRuntimeAuthorityTest::read).collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    private static String source(String relativePath) throws Exception {
        return Files.readString(sourceRoot().resolve(relativePath));
    }

    private static String protocolSource(String fileName) throws Exception {
        Path module = sourceRoot().getParent().getParent().getParent().getParent().getParent().getParent().getParent();
        return Files.readString(module.getParent().resolve("surprising-aeron-protocol")
                .resolve("src/main/java/com/surprising/aeron/protocol").resolve(fileName));
    }

    private static Path sourceRoot() throws Exception {
        Path classes = Path.of(TradingCoreRuntimeAuthorityTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        return classes.getParent().getParent().resolve("src/main/java/com/surprising/aeron/service");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("cannot read production source " + path, exception);
        }
    }

    private record ForbiddenFixture(String name, String source) {}
}
