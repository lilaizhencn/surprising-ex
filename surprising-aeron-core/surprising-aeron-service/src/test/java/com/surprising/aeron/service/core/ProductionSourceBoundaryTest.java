package com.surprising.aeron.service.core;

import com.surprising.aeron.bootstrap.TradingOwnerLoop;
import com.surprising.aeron.model.OwnerLogContext;
import com.surprising.aeron.service.order.ClusterCommandWindow;
import com.surprising.aeron.service.order.CommandSlot;
import com.surprising.aeron.service.order.DirectCommandSlot;
import com.surprising.aeron.service.order.OrderBatchPending;
import com.surprising.aeron.service.order.OwnerCommandPipelineState;
import com.surprising.aeron.service.order.PendingMatchingRing;
import com.surprising.aeron.service.order.commit.OrderedCommitCoordinator;
import com.surprising.aeron.support.egress.ClusterServiceEgress;
import com.surprising.aeron.support.ingress.OwnerIngressRing;
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

/**
 * 用反射及有限源码检查约束已有架构：具体运行时依赖、单输入环、Direct 撮合、显式 Lane 查询和业务状态所有权。它检查列出的结构与文本，不代替运行时线程、资金或恢复验证；正则扫描也不是完整语法分析。
 */
class ProductionSourceBoundaryTest {
    /**
     * 检查交易运行时只实现关闭契约，各业务命令 owner 字段和构造器直接使用实际运行时；控制槽内 owner 同样如此，命令目录不再保留 Context 命名的来源文件。
     */
    @Test
    void d5CommandsUseTheConcreteRuntimeWithoutCapabilityInterfaces() throws Exception {
        assertThat(TradingCoreRuntime.class.getInterfaces()).containsExactly(AutoCloseable.class);
        for (Class<?> /* 当前要求直接依赖实际运行时的业务命令类型 */ handler : java.util.List.of(
                com.surprising.aeron.service.account.command.BalanceTransferCommands.class,
                com.surprising.aeron.service.instrument.command.InstrumentConfigurationCommands.class,
                com.surprising.aeron.service.fee.command.FeePolicyCommands.class,
                com.surprising.aeron.service.funding.command.PerpetualFundingCommands.class,
                com.surprising.aeron.service.risk.command.RiskCommands.class,
                com.surprising.aeron.service.liquidation.command.LiquidationCommands.class,
                com.surprising.aeron.service.adl.command.AdlCommands.class,
                com.surprising.aeron.service.insurance.command.InsuranceFundCommands.class,
                com.surprising.aeron.service.position.command.PositionCommands.class,
                com.surprising.aeron.service.position.command.LeverageCommands.class,
                com.surprising.aeron.service.settlement.command.InstrumentSettlementCommands.class,
                com.surprising.aeron.service.trigger.command.TriggerOrderCommands.class)) {
            assertThat(handler.getDeclaredField("owner").getType()).isEqualTo(TradingCoreRuntime.class);
            assertThat(handler.getConstructor(TradingCoreRuntime.class)).isNotNull();
        }
        assertThat(DirectCommandSlot.class.getDeclaredField("owner").getType()).isEqualTo(TradingCoreRuntime.class);
        // 从当前目录向上找到的交易仓库根，找不到即测试失败
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("surprising-aeron-core"))) {
            root = root.getParent();
            if (root == null) throw new AssertionError("repository root unavailable");
        }
        // 当前仓库生产业务命令目录，检查不存在 Context 命名文件
        Path commands = root.resolve("surprising-aeron-core/surprising-aeron-service/src/main/java/"
                + "com/surprising/aeron/service/core/command");
        try (/* 遍历命令目录的文件流，退出资源作用域时关闭 */ var files = Files.walk(commands)) {
            assertThat(files.filter(/* 文件筛选回调的当前路径，访问器中为已统一斜杠的路径文本 */ path -> path.getFileName().toString().endsWith("Context.java")))
                    .isEmpty();
        }
    }

    /**
     * 用字段和方法反射排除旧输入中转，核对 Owner 与外层线程共用同一种输入环，活动控制槽直接引用环条目。
     */
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

    /**
     * 按列出的旧 API、字段与文件名检查已删除的撮合及结算旁路不再存在，保留 Direct 撮合和所属 Lane 结算入口。
     */
    @Test
    void d3TradingHasOnlyDirectApisAndNoOwnerSettlementFallback() throws Exception {
        // Matcher 适配器全部声明方法名，和列出的旧接口清单比较
        var adapterMethods = java.util.Arrays.stream(
                com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.class.getDeclaredMethods())
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
        // 从当前目录向上找到的交易仓库根，找不到即测试失败
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("surprising-aeron-core"))) {
            root = root.getParent();
            if (root == null) throw new AssertionError("repository root unavailable");
        }
        assertThat(Files.exists(root.resolve("surprising-aeron-core/surprising-aeron-service/"
                + "src/main/java/com/surprising/aeron/service/core/MatcherCommandSubmission.java"))).isFalse();
    }

    /**
     * 检查旧 Owner 结算派发和兼容扫描接口已移除，批次构造保持两个参数，Lane 响应方法必须实际实现而非默认可选。
     */
    @Test
    void d2HasNoOwnerDirectDispatchScanCallbackOrOptionalLaneResponse() throws Exception {
        assertThat(java.util.Arrays.stream(com.surprising.aeron.service.core.TradingAccountRuntime.class
                .getDeclaredMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("dispatchOwnerControlledSettlement");
        assertThat(java.util.Arrays.stream(ClusterCommandWindow.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)).doesNotContain("complete");
        assertThat(java.util.Arrays.stream(com.surprising.aeron.service.settlement.MatcherSettlementEvent.class
                .getDeclaredMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("markMatcherOwnedPublication", "matcherOwnedPublication");
        assertThat(java.util.Arrays.stream(OrderBatchPending.class.getDeclaredConstructors())
                .mapToInt(java.lang.reflect.Constructor::getParameterCount)).containsOnly(2);
        assertThat(java.util.Arrays.stream(com.surprising.aeron.service.core.index.ActiveOrderIndex.class
                .getDeclaredMethods()).map(java.lang.reflect.Method::getName)).doesNotContain("ids");
        for (String /* 本次必须由 Lane 实际实现的响应方法名 */ name : java.util.List.of("preparedResponse", "preparedResponseLength")) {
            // 反射取得的 Lane 响应方法，检查不是默认实现
            var method = com.surprising.aeron.service.order.state.LaneOrderResultTarget.class.getMethod(name);
            assertThat(method.isDefault()).as("Lane response contract %s", name).isFalse();
        }
    }

    /**
     * 读取四个指定 Owner/窗口源码，要求只携带 long 会话号且不提及网络会话类型；另检查日志上下文和传输会话未冒充 Aeron 接口，出口未保留指定未实现方法及旧传输接口。
     */
    @Test
    void ownerCarriesSessionIdsAndNeitherD1HelperImplementsAnAeronInterface() throws IOException {
        // 从当前目录向上找到的交易仓库根，找不到即测试失败
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("surprising-aeron-core"))) {
            root = root.getParent();
            if (root == null) throw new AssertionError("repository root unavailable");
        }
        // Aeron 服务生产 Java 源码根目录
        Path sources = root.resolve("surprising-aeron-core/surprising-aeron-service/src/main/java");
        for (Class<?> /* 当前待反射或读取源文件的类型；业务泛型检查中为待排除的正文类型名 */ type : java.util.List.of(TradingCoreOwner.class, TradingOwnerLoop.class,
                OwnerIngressRing.class, ClusterCommandWindow.class)) {
            // 当前指定或遍历中的生产 Java 文件全文，检查时也包含注释
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

    /**
     * 读取指定账户查询源文件，排除隐式 Lane 定位文本；用正则检查列出的 runtime 业务读取第一参数都为 lane，且至少检查到十一处，不能泛化成全部源码所有调用。
     */
    @Test
    void accountQueryProjectionReadsOnlyItsExplicitLane() throws IOException {
        // 从当前目录向上找到的交易仓库根，找不到即测试失败
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("surprising-aeron-core"))) {
            root = root.getParent();
            if (root == null) throw new AssertionError("repository root unavailable");
        }
        // 当前指定或遍历中的生产 Java 文件全文，检查时也包含注释
        var source = Files.readString(root.resolve("surprising-aeron-core/surprising-aeron-service/src/main/java/"
                + "com/surprising/aeron/service/query/state/RuntimeStateQueryService.java"));
        assertThat(source).doesNotContain("currentLaneCommandScope", "accountLaneForUser", "onLane(");
        // 在账户查询正文中匹配指定 runtime 读取调用及首参的正则游标
        var reads = Pattern.compile("runtime\\.(?:user|order|reservation|position|balancesForUser|"
                + "reservationCountForUser|positionCountForUser|leverageKeysForUser|reservationIdsForUser|"
                + "positionKeysForUser|leverage|orderIdByClientValue)\\(\\s*([^,)]*)").matcher(source);
        // 已检查的显式 Lane 读取调用数量，防止正则一个也没匹配
        int count = 0;
        while (reads.find()) {
            assertThat(reads.group(1)).as("explicit Lane on %s", reads.group()).isEqualTo("lane");
            count++;
        }
        assertThat(count).isGreaterThan(10);
    }

    /**
     * 反射排除指定 Owner 账户正文表与累计资金字段，检查 Map/LongObjectHashMap 泛型不持久保留列出的业务正文类型；活单路由只能含订单、用户、标的三字段。
     */
    @Test
    void ownerRetainsNoPersistentLaneBusinessViews() {
        // 运行时声明字段流，供检查指定旧正文表名称不存在
        var fields = java.util.Arrays.stream(com.surprising.aeron.service.core.TradingAccountRuntime.class.getDeclaredFields());
        assertThat(fields.map(java.lang.reflect.Field::getName)).doesNotContain(
                "publishedOrders", "publishedUsers", "publishedAlgoOrders", "publishedTriggerOrders",
                "publishedLiquidations", "publishedRiskSnapshots");
        // 不允许 Owner 持久表保存的四类账户正文类型名
        var businessTypes = java.util.Set.of("CoreAlgoOrderState", "CoreTriggerOrderState", "LiquidationRuntime", "RiskSnapshotRuntime");
        for (var /* 当前待核对类型或字段名的反射字段 */ field : com.surprising.aeron.service.core.TradingAccountRuntime.class.getDeclaredFields()) {
            if (!java.util.Map.class.isAssignableFrom(field.getType())
                    && !field.getType().getName().endsWith("LongObjectHashMap")) continue;
            assertThat(businessTypes.stream().anyMatch(/* 当前待反射或读取源文件的类型；业务泛型检查中为待排除的正文类型名 */ type -> field.getGenericType().getTypeName().contains(type)))
                    .as("persistent Owner business view %s", field.getName()).isFalse();
        }
        assertThat(java.util.Arrays.stream(com.surprising.aeron.service.core.index.ActiveOrderIndex.IndexedOrder.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).containsExactlyInAnyOrder("orderId", "userId", "instrumentId");
        assertThat(java.util.Arrays.stream(TradingCoreRuntime.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .doesNotContain("commandFundsAccumulator");
    }

    /**
     * 从仓库根遍历生产 Java 文件，跳过版本、索引及构建等指定目录，按正则和文件名收集测试/压测旁路；要求无命中，扫描含注释文本且只覆盖列出的模式。
     */
    @Test
    void productionSourcesDoNotContainTestHooksOrBenchmarkBypasses() throws IOException {
        // 从当前目录向上找到的交易仓库根，找不到即测试失败
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("surprising-aeron-core"))) {
            root = root.getParent();
            if (root == null) throw new AssertionError("repository root unavailable");
        }
        // 生产源码中需要排除的既有测试及压测旁路文本模式
        var forbidden = Pattern.compile(
                "ForTest|ForBenchmark|FaultInjector|BENCHMARK_|benchmark\\.skip|"
                        + "beforeActivationObserver|injected (?:mutable projection|.*commit) failure|"
                        + "\\bpublic static \\w+ simulate\\w*\\(");
        // 扫描到的违规生产 Java 文件路径，结束应为空
        var violations = new ArrayList<String>();
        Files.walkFileTree(root, /* 本测试逐目录、逐文件收集生产源码违规路径的文件访问器，不修改任何业务源文件。 */ new SimpleFileVisitor<>() {
            /**
             * 按目录名跳过版本元数据、代码索引、构建、前端依赖及基准模块，其余继续向下扫描。
             * @param directory 当前即将进入的目录
             * @param attributes 文件系统提供的当前属性，本检查不读取
             */
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return switch (directory.getFileName().toString()) {
                    case ".git", ".codegraph", "target", "node_modules", "surprising-aeron-benchmarks" ->
                            FileVisitResult.SKIP_SUBTREE;
                    default -> FileVisitResult.CONTINUE;
                };
            }

            /**
             * 只检查位于生产 Java 源路径且扩展名为 java 的文件，源码或文件名命中禁用模式则记录路径；始终继续遍历后续文件。
             * @param file 当前访问文件路径
             * @param attributes 文件系统提供的当前属性，本检查不读取
             */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                // 文件筛选回调的当前路径，访问器中为已统一斜杠的路径文本
                String path = file.toString().replace('\\', '/');
                if (path.contains("/src/main/java/") && path.endsWith(".java")) {
                    // 当前指定或遍历中的生产 Java 文件全文，检查时也包含注释
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
