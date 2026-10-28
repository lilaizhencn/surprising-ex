package com.surprising.aeron.config;

import com.surprising.aeron.controller.AeronTradingClusterService;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.support.egress.ClusterServiceEgress;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证真实配置绑定、Spring 到 Owner 的拓扑传递，以及六产品线恢复不读取新的节点路由。 */
class CoreConfigurationTest {
    /** 本测试创建的 YAML 目录，测试结束后由 JUnit 清理。 */
    @TempDir
    Path directory;

    /** 发布的 YAML 与 Java 默认值应一致，覆盖全部分片、种子和队列参数。 */
    @Test
    void applicationYamlAndJavaDefaultsDescribeTheSameTopology() {
        // 读取真实发布配置并通过容器取得 Bean，避免直接调用未代理的 Bean 方法。
        try (var context = application().run("--surprising.aeron.product-line=SPOT",
                "--surprising.aeron.hostnames=localhost")) {
            assertThat(context.getBean(LaneTopology.class)).isEqualTo(com.surprising.aeron.service.core.CoreStateTestFixtures.DEFAULT_TOPOLOGY);
        }
    }

    /** 命令行覆盖系统属性和 YAML，真实 Owner 的账户、Matcher 及快照应共用绑定后的拓扑。 */
    @Test
    void springOwnerUsesYamlAndCommandLineWithoutRereadingSystemProperties() throws Exception {
        // 创建不同于默认值的节点 YAML，覆盖种子与三种队列参数。
        Path yaml = writeYaml();
        // 故意提供非法低优先级属性，检查运行时内部不会绕过 Spring 再读一遍。
        String previous = System.getProperty("surprising.aeron.account-lanes");
        System.setProperty("surprising.aeron.account-lanes", "3");
        try (/* 只装配配置、出口与真实 Owner，不启动网络驱动。 */ var context = application().run(
                "--spring.config.location=" + yaml.toUri(),
                "--surprising.aeron.product-line=SPOT", "--surprising.aeron.hostnames=localhost",
                "--surprising.aeron.account-lanes=8")) {
            // Spring 绑定后唯一的启动拓扑。
            var topology = context.getBean(LaneTopology.class);
            var runtimeConfiguration = context.getBean(CoreRuntimeConfiguration.class);
            assertThat(runtimeConfiguration.matcherGroupSize()).isEqualTo(17);
            assertThat(runtimeConfiguration.laneInitialEntities()).isEqualTo(32);
            assertThat(topology).isEqualTo(new LaneTopology(2, 8, 42, 32, 64, 128));
            // 用最小 Cluster 元数据启动真实 Owner 线程，并捕获它实际使用的拓扑。
            var cluster = cluster();
            var egress = context.getBean(ClusterServiceEgress.class);
            var service = context.getBean(AeronTradingClusterService.class);
            egress.start(cluster);
            try {
                service.onStart(cluster, null);
                try (var restored = com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec.decodePassive(
                        service.captureSnapshot(1, 0), ProductLine.SPOT, runtimeConfiguration, topology)) {
                    assertThat(restored.runtimeState().topology()).isEqualTo(topology);
                }
            } finally {
                service.onTerminate(cluster);
                egress.clear();
            }
        } finally {
            restoreProperty("surprising.aeron.account-lanes", previous);
        }
    }

    /** 环境变量应覆盖 YAML，而命令行仍能覆盖环境变量。 */
    @Test
    void environmentAndCommandLineUseTheSameBindingAsYaml() throws Exception {
        // 在真实 Spring 环境中模拟进程环境变量，不改当前进程的环境。
        var environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                "SURPRISING_AERON_MATCHINGENGINES", "4",
                "SURPRISING_AERON_ACCOUNTLANES", "8",
                "SURPRISING_AERON_ACCOUNTLANESEED", "99")));
        // 启动时使用的应用实例，仅装配配置与 Owner 资源。
        var application = application();
        application.setEnvironment(environment);
        try (/* Spring 配置上下文，不启动 Owner 工作线程。 */ var context = application.run(
                "--spring.config.location=" + writeYaml().toUri(),
                "--surprising.aeron.product-line=SPOT", "--surprising.aeron.hostnames=localhost",
                "--surprising.aeron.account-lanes=16")) {
            assertThat(context.getBean(LaneTopology.class))
                    .isEqualTo(new LaneTopology(4, 16, 99, 32, 64, 128));
        }
    }

    /** 非二次幂队列容量必须在创建启动拓扑时拒绝，不能延迟到交易线程执行。 */
    @Test
    void invalidYamlCapacityFailsBeforeOwnerStarts() throws Exception {
        // 其他参数沿用合法 YAML，只覆盖一个非法队列容量。
        Path yaml = writeYaml();
        assertThatThrownBy(() -> application().run("--spring.config.location=" + yaml.toUri(),
                "--surprising.aeron.product-line=SPOT", "--surprising.aeron.hostnames=localhost",
                "--surprising.aeron.account-lane-queue-capacity=3"))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("accountLaneQueueCapacity");
    }

    /**
     * 每条产品线恢复快照中的分片与路由种子，队列容量取显式部署拓扑，不重新读取节点属性。
     * @param product 本次恢复验证的独立产品线
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void snapshotRoutingAndDeploymentCapacitiesAreRestoredExplicitly(ProductLine product) {
        // 显式传入的旧节点拓扑，账户与 Matcher 应同时使用它。
        var topology = new LaneTopology(2, 4, 42, 32, 64, 128);
        var deployment = new LaneTopology(4, 8, 99, 64, 128, 256);
        var expected = new LaneTopology(2, 4, 42, 64, 128, 256);
        var configuration = CoreRuntimeConfiguration.standalone();
        // 暂存节点当前属性，确保测试结束后恢复，不影响其他产品线。
        String previous = System.getProperty("surprising.aeron.account-lanes");
        System.setProperty("surprising.aeron.account-lanes", "3");
        try (/* 首次启动显式拓扑的独立运行时。 */ var original = new TradingCoreRuntime(product, topology)) {
            assertThat(original.runtimeState().topology()).isSameAs(topology);
            // 捕获真实 Matcher 与账户的配对快照，随后按该快照恢复。
            com.surprising.aeron.service.order.CoreTestCompletion.applyAsynchronously(original,
                    new com.surprising.aeron.protocol.CoreMessage(com.surprising.aeron.protocol.CoreMessageHeader.command(
                            com.surprising.aeron.protocol.CoreMessageType.ADJUST_BALANCE, new java.util.UUID(1, 1), product,
                            com.surprising.aeron.protocol.CommandSource.OPERATIONS, 1, 1, 7, 1, 1),
                            com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(
                                    new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 100))));
            byte[] snapshot = original.snapshot(101);
            try (/* 从快照恢复的独立运行时，不读取当前节点路由属性。 */
                    var restored = com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec.decode(snapshot, product, configuration, deployment)) {
                assertThat(restored.runtimeState().topology()).isEqualTo(expected);
                assertThat(restored.stateHash()).isEqualTo(original.stateHash());
                try (var again = com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec.decode(
                        restored.snapshot(102), product, configuration, deployment)) {
                    assertThat(again.runtimeState().topology()).isEqualTo(expected);
                    assertThat(again.stateHash()).isEqualTo(original.stateHash());
                }
            }
        } finally {
            restoreProperty("surprising.aeron.account-lanes", previous);
        }
    }

    /**
     * 写入非默认节点拓扑，供真实 Spring 配置加载测试使用。
     * @return 当前测试临时目录中的 YAML 路径
     */
    private Path writeYaml() throws Exception {
        return Files.writeString(directory.resolve("application.yml"), """
                surprising:
                  aeron:
                    matching-engines: 2
                    account-lanes: 4
                    account-lane-seed: 42
                    matcher-window-size: 32
                    matching-completion-capacity: 64
                    account-lane-queue-capacity: 128
                    matcher-group-size: 17
                    lane-initial-entities: 32
                """);
    }

    /** 创建实际配置和 Owner 的 Spring 装配入口，不包含 Aeron 网络生命周期组件。 */
    private static SpringApplication application() {
        // 测试保留真实配置加载和依赖注入，避免测试替身掩盖传参遗漏。
        var application = new SpringApplication(CoreConfiguration.class, AeronTradingClusterService.class, ClusterServiceEgress.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        return application;
    }

    /** 创建仅提供 Owner 启动与快照所需元数据的 Cluster 替身。 */
    private static Cluster cluster() {
        return (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (/* 当前 Cluster 代理实例，测试不读取。 */ proxy,
                 /* 要查询的 Cluster 方法。 */ method,
                 /* 当前查询的参数，元数据接口不使用。 */ args) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "memberId" -> 0;
                    case "time", "logPosition" -> 0L;
                    case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                    default -> throw new AssertionError("unexpected Cluster API " + method.getName());
                });
    }

    /**
     * 还原本测试临时改动的系统属性。
     * @param key 需要还原的属性名
     * @param previous 测试前原值，空表示原先没有该属性
     */
    private static void restoreProperty(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }
}
