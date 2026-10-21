package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.orchestration.commit.OrderedCommitCoordinator;
import com.surprising.aeron.service.orchestration.egress.ClusterServiceEgress;
import com.surprising.aeron.service.orchestration.ingress.OwnerIngressRing;
import com.surprising.aeron.service.orchestration.pipeline.ClusterCommandWindow;
import com.surprising.aeron.service.orchestration.pipeline.CommandSlot;
import com.surprising.aeron.service.orchestration.pipeline.DirectCommandSlot;
import com.surprising.aeron.service.orchestration.pipeline.OrderBatchPending;
import com.surprising.aeron.service.orchestration.pipeline.OwnerCommandPipelineState;
import com.surprising.aeron.service.orchestration.pipeline.PendingMatchingRing;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSourceBoundaryTest {
    @Test
    void d5CommandsUseTheConcreteRuntimeWithoutCapabilityInterfaces() throws Exception {
        assertThat(TradingCoreRuntime.class.getInterfaces()).containsExactly(AutoCloseable.class);
        for (Class<?> handler : java.util.List.of(
                com.surprising.aeron.service.command.balance.BalanceTransferCommands.class,
                com.surprising.aeron.service.command.instrument.InstrumentConfigurationCommands.class,
                com.surprising.aeron.service.command.fee.FeePolicyCommands.class,
                com.surprising.aeron.service.command.funding.PerpetualFundingCommands.class,
                com.surprising.aeron.service.command.risk.RiskCommands.class,
                com.surprising.aeron.service.command.liquidation.LiquidationCommands.class,
                com.surprising.aeron.service.command.adl.AdlCommands.class,
                com.surprising.aeron.service.command.insurance.InsuranceFundCommands.class,
                com.surprising.aeron.service.command.position.PositionCommands.class,
                com.surprising.aeron.service.command.leverage.LeverageCommands.class,
                com.surprising.aeron.service.command.settlement.InstrumentSettlementCommands.class,
                com.surprising.aeron.service.command.trigger.TriggerOrderCommands.class)) {
            assertThat(handler.getDeclaredField("owner").getType()).isEqualTo(TradingCoreRuntime.class);
            assertThat(handler.getConstructor(TradingCoreRuntime.class)).isNotNull();
        }
        assertThat(java.util.Arrays.stream(DirectCommandSlot.class.getDeclaredClasses())
                .flatMap(type -> java.util.Arrays.stream(type.getDeclaredFields()))
                .filter(field -> field.getName().equals("owner")))
                .isNotEmpty().allSatisfy(field -> assertThat(field.getType()).isEqualTo(TradingCoreRuntime.class));
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("surprising-aeron-core"))) {
            root = root.getParent();
            if (root == null) throw new AssertionError("repository root unavailable");
        }
        Path commands = root.resolve("surprising-aeron-core/surprising-aeron-service/src/main/java/"
                + "com/surprising/aeron/service/command");
        try (var files = Files.walk(commands)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith("Context.java")))
                    .isEmpty();
        }
    }

    @Test
    void d4UsesOneInputRingWithoutAnOwnerRelayOrReturnQueue() throws Exception {
        assertThat(java.util.Arrays.stream(TradingOwnerLoop.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).doesNotContain("availableInput", "unpublishedInput");
        assertThat(java.util.Arrays.stream(OwnerCommandPipelineState.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).doesNotContain("pendingIngress");
        assertThat(java.util.Arrays.stream(TradingCoreOwner.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)).doesNotContain("enqueueCommittedCommand", "awaitIngressCapacity");
        assertThat(TradingCoreOwner.class.getDeclaredField("ingress").getType()).isEqualTo(OwnerIngressRing.class);
        assertThat(TradingOwnerLoop.class.getDeclaredField("input").getType()).isEqualTo(OwnerIngressRing.class);
        assertThat(OwnerCommandPipelineState.class.getDeclaredField("activeControl").getType())
                .isEqualTo(OwnerIngressRing.Entry.class);
    }

    @Test
    void d3TradingHasOnlyDirectApisAndNoOwnerSettlementFallback() throws Exception {
        var adapterMethods = java.util.Arrays.stream(
                com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).toList();
        assertThat(adapterMethods).doesNotContain("place", "placeAsync", "placeWithEvidence",
                "cancel", "cancelAsync", "cancelWithEvidence", "cancelForContinuation", "cancelBatchAsync",
                "replaceOrder", "replaceOrderAsync", "replaceAsync", "replaceWithEvidence",
                "executeWithEvidence", "executeWithEvidenceSync", "executeControlWithEvidenceSync",
                "executeShardWithEvidenceSync", "executeAfterCancellations", "executeAfterCancellationsSync",
                "cancelBatchOrderedAsync", "prepareOrderRoutesAsync");
        assertThat(java.util.Arrays.stream(TradingCoreRuntime.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)).doesNotContain("applyMatchesOnAccountLanes", "matchingOrder");
        assertThat(java.util.Arrays.stream(OrderedCommitCoordinator.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)).doesNotContain("initialMatcherSettlementPlan",
                        "dispatchReadyPlaceSettlements", "dispatchPartitionSettlements", "completeDispatchedCancel");
        assertThat(java.util.Arrays.stream(CommandSlot.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)).doesNotContain("cancelEvent", "settlementPlanBuffer", "dispatchOnly");
        assertThat(java.util.Arrays.stream(OrderBatchPending.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).doesNotContain("pipelinedMatchingResults",
                        "pipelinedMatchingResultCount", "deferredSettlementItemIndexes", "deferredCancellationOrderIds");
        assertThat(java.util.Arrays.stream(PendingMatchingRing.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)).doesNotContain("readyPartitionMask",
                        "collectPartitionDispatchHeads", "completePartitionDispatchKnown", "dispatchHead");
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("surprising-aeron-core"))) {
            root = root.getParent();
            if (root == null) throw new AssertionError("repository root unavailable");
        }
        assertThat(Files.exists(root.resolve("surprising-aeron-core/surprising-aeron-service/"
                + "src/main/java/com/surprising/aeron/service/orchestration/MatcherCommandSubmission.java"))).isFalse();
    }

    @Test
    void d2HasNoOwnerDirectDispatchScanCallbackOrOptionalLaneResponse() throws Exception {
        assertThat(java.util.Arrays.stream(com.surprising.aeron.service.state.TradingRuntimeState.class
                .getDeclaredMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("dispatchOwnerControlledSettlement");
        assertThat(java.util.Arrays.stream(ClusterCommandWindow.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)).doesNotContain("complete");
        assertThat(java.util.Arrays.stream(com.surprising.aeron.service.state.settlement.MatcherSettlementEvent.class
                .getDeclaredMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("markMatcherOwnedPublication", "matcherOwnedPublication");
        assertThat(java.util.Arrays.stream(OrderBatchPending.class.getDeclaredConstructors())
                .mapToInt(java.lang.reflect.Constructor::getParameterCount)).containsOnly(2);
        assertThat(java.util.Arrays.stream(com.surprising.aeron.service.state.index.ActiveOrderIndex.class
                .getDeclaredMethods()).map(java.lang.reflect.Method::getName)).doesNotContain("ids");
        for (String name : java.util.List.of("preparedResponse", "preparedResponseLength")) {
            var method = com.surprising.aeron.service.state.order.LaneOrderResultTarget.class.getMethod(name);
            assertThat(method.isDefault()).as("Lane response contract %s", name).isFalse();
        }
    }

    @Test
    void ownerCarriesSessionIdsAndNeitherD1HelperImplementsAnAeronInterface() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("surprising-aeron-core"))) {
            root = root.getParent();
            if (root == null) throw new AssertionError("repository root unavailable");
        }
        Path sources = root.resolve("surprising-aeron-core/surprising-aeron-service/src/main/java");
        for (Class<?> type : java.util.List.of(TradingCoreOwner.class, TradingOwnerLoop.class,
                OwnerIngressRing.class, ClusterCommandWindow.class)) {
            String source = Files.readString(sources.resolve(type.getName().replace('.', '/') + ".java"));
            assertThat(source).as("Owner session boundary in %s", type.getSimpleName())
                    .doesNotContain("ClientSession", "TransportSession");
            assertThat(source).contains("long sessionId");
        }
        assertThat(io.aeron.cluster.service.Cluster.class.isAssignableFrom(OwnerLogContext.class)).isFalse();
        assertThat(io.aeron.cluster.service.ClientSession.class.isAssignableFrom(
                ClusterServiceEgress.TransportSession.class)).isFalse();
        assertThat(Files.readString(sources.resolve(OwnerLogContext.class.getName().replace('.', '/') + ".java")))
                .doesNotContain("UnsupportedOperationException");
        assertThat(Files.readString(sources.resolve(ClusterServiceEgress.class.getName().replace('.', '/') + ".java")))
                .doesNotContain("UnsupportedOperationException", "tryClaim(", "DirectBufferVector");
    }

    @Test
    void accountQueryProjectionReadsOnlyItsExplicitLane() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("surprising-aeron-core"))) {
            root = root.getParent();
            if (root == null) throw new AssertionError("repository root unavailable");
        }
        var source = Files.readString(root.resolve("surprising-aeron-core/surprising-aeron-service/src/main/java/"
                + "com/surprising/aeron/service/state/query/RuntimeStateQueryService.java"));
        assertThat(source).doesNotContain("currentLaneCommandScope", "accountLaneForUser", "onLane(");
        var reads = Pattern.compile("runtime\\.(?:user|order|reservation|position|balancesForUser|"
                + "reservationCountForUser|positionCountForUser|leverageKeysForUser|reservationIdsForUser|"
                + "positionKeysForUser|leverage|orderIdByClientValue)\\(\\s*([^,)]*)").matcher(source);
        int count = 0;
        while (reads.find()) {
            assertThat(reads.group(1)).as("explicit Lane on %s", reads.group()).isEqualTo("lane");
            count++;
        }
        assertThat(count).isGreaterThan(10);
    }

    @Test
    void ownerRetainsNoPersistentLaneBusinessViews() {
        var fields = java.util.Arrays.stream(com.surprising.aeron.service.state.TradingRuntimeState.class.getDeclaredFields());
        assertThat(fields.map(java.lang.reflect.Field::getName)).doesNotContain(
                "publishedOrders", "publishedUsers", "publishedAlgoOrders", "publishedTriggerOrders",
                "publishedLiquidations", "publishedRiskSnapshots");
        var businessTypes = java.util.Set.of("CoreAlgoOrderState", "CoreTriggerOrderState", "LiquidationRuntime", "RiskSnapshotRuntime");
        for (var field : com.surprising.aeron.service.state.TradingRuntimeState.class.getDeclaredFields()) {
            if (!java.util.Map.class.isAssignableFrom(field.getType())
                    && !field.getType().getName().endsWith("LongObjectHashMap")) continue;
            assertThat(businessTypes.stream().anyMatch(type -> field.getGenericType().getTypeName().contains(type)))
                    .as("persistent Owner business view %s", field.getName()).isFalse();
        }
        assertThat(java.util.Arrays.stream(com.surprising.aeron.service.state.index.ActiveOrderIndex.IndexedOrder.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).containsExactlyInAnyOrder("orderId", "userId", "instrumentId");
        assertThat(java.util.Arrays.stream(TradingCoreRuntime.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .doesNotContain("commandFundsAccumulator");
    }

    @Test
    void productionSourcesDoNotContainTestHooksOrBenchmarkBypasses() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("surprising-aeron-core"))) {
            root = root.getParent();
            if (root == null) throw new AssertionError("repository root unavailable");
        }
        var forbidden = Pattern.compile(
                "ForTest|ForBenchmark|FaultInjector|BENCHMARK_|benchmark\\.skip|"
                        + "beforeActivationObserver|injected (?:mutable projection|.*commit) failure|"
                        + "\\bpublic static \\w+ simulate\\w*\\(");
        var violations = new ArrayList<String>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return switch (directory.getFileName().toString()) {
                    case ".git", ".codegraph", "target", "node_modules", "surprising-aeron-benchmarks" ->
                            FileVisitResult.SKIP_SUBTREE;
                    default -> FileVisitResult.CONTINUE;
                };
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String path = file.toString().replace('\\', '/');
                if (path.contains("/src/main/java/") && path.endsWith(".java")) {
                    String source = Files.readString(file);
                    if (forbidden.matcher(source).find()
                            || file.getFileName().toString().matches(".*(?:Test|Fixture|Benchmark|FaultAgent)\\.java")) {
                        violations.add(file.toString());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertThat(violations).as("test support must remain outside production sources").isEmpty();
    }
}
