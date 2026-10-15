package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ProductionSourceBoundaryTest {
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
