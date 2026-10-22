package com.surprising.aeron.config;

import java.util.BitSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 检查 CPU 配置的绑定、默认关闭行为，以及 Linux 绑定和 macOS 跳过规则。 */
class TradingCpuAffinityTest {
    /** Java 默认值和 application.yml 必须一致，均不能在用户没有配置时开启 CPU 绑定。 */
    @Test
    void applicationFileAndJavaConfigurationBothDisableAffinityByDefault() {
        assertThat(new CoreConfiguration().getCpuAffinity().isEnabled()).isFalse();
        // 读取发布包中实际使用的配置文件，避免只验证 Java 字段默认值。
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        // 取得 YAML 转换后的属性，核对开关的实际配置文本。
        var properties = yaml.getObject();
        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("surprising.aeron.cpu-affinity.enabled")).isEqualTo("false");
        assertThat(bind(yaml).getCpuAffinity().isEnabled()).isFalse();
    }

    /** 显式 YAML 应准确绑定四个角色的 CPU 编号，不得调换 Owner、Matcher 或账户 Lane。 */
    @Test
    void bindsExplicitSwitchAndRoleCpuIdsFromYaml() {
        // 构造最小的内存 YAML，仅包含本测试要验证的 CPU 配置。
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ByteArrayResource("""
                surprising:
                  aeron:
                    cpu-affinity:
                      enabled: true
                      owner-cpu: 2
                      matcher-cpu: 8
                      lane0-cpu: 16
                      lane1-cpu: 32
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // 读取通过 Spring Binder 得到的配置，确认与直接构造 Java 对象时的顺序一致。
        var cpus = bind(yaml).getCpuAffinity();
        assertThat(cpus.isEnabled()).isTrue();
        assertThat(new int[]{cpus.getOwnerCpu(), cpus.getMatcherCpu(), cpus.getLane0Cpu(), cpus.getLane1Cpu()})
                .containsExactly(2, 8, 16, 32);
    }

    /**
     * 用真实 Spring Binder 把 YAML 属性绑定到 CoreConfiguration，复现启动时的配置装配。
     * @param yaml 已设置好资源的 YAML 属性读取器
     */
    private static CoreConfiguration bind(YamlPropertiesFactoryBean yaml) {
        // Binder 能读取的属性源；这里只在测试里把 YAML 键值放进去。
        var source = new MapConfigurationPropertySource();
        yaml.getObject().forEach((/* YAML 属性名，需要转换成字符串交给 Binder。 */ key,
                /* 对应的原始配置值，保留布尔值和数字等类型。 */ value) -> source.put(key.toString(), value));
        // 让 Binder 填充这个实际配置实例，测试后返回它检查各个字段。
        var configuration = new CoreConfiguration();
        new Binder(source).bind("surprising.aeron", Bindable.ofInstance(configuration));
        return configuration;
    }

    /** 关闭功能时，无效 CPU 编号和额外分片都不应触发本地绑定或阻止线程启动。 */
    @Test
    void disabledDoesNotValidateCpuAssignmentsOrRestrictWorkerTopology() {
        // 故意使用未配置的 CPU 编号，验证关闭分支确实不会检查或使用它们。
        var disabled = new TradingCpuAffinity(false, -1, -1, -1, -1);
        disabled.validateConfiguration(LaneTopology.characterization());
        startsAndStopsOwner(disabled);
        try (/* Owner 的空操作恢复凭据，关闭它不应调用系统绑定接口。 */ var owner = disabled.owner();
             /* 即便传入额外 Matcher 编号，关闭功能时也只返回空操作凭据。 */ var matcher = disabled.matcher(7);
             /* 额外账户 Lane 同样不应因为未启用的配置被拒绝。 */ var lane = disabled.accountLane(7)) {
            assertThat(Thread.currentThread().isAlive()).isTrue();
        }
    }

    /** 校验 CPU 编号不得为负或重复，并且每个编号都必须属于当前允许的 CPU 范围。 */
    @Test
    void validatesDistinctNonNegativeCpuIdsAndTheAllowedMask() {
        // 四个不同的 CPU 编号，对应四种交易线程角色。
        int[] cpus = {2, 8, 16, 32};
        TradingCpuAffinity.validateCpuIds(cpus);
        // 先允许全部配置 CPU，再移除一个 CPU，验证范围检查会明确指出缺失项。
        BitSet allowed = new BitSet();
        for (/* 当前加入允许范围的 CPU 编号。 */ int cpu : cpus) allowed.set(cpu);
        TradingCpuAffinity.validateAllowedCpus(cpus, allowed);
        allowed.clear(16);
        assertThatThrownBy(() -> TradingCpuAffinity.validateAllowedCpus(cpus, allowed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("16");
        assertThatThrownBy(() -> TradingCpuAffinity.validateCpuIds(new int[]{1, 2, 2, 4}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TradingCpuAffinity.validateCpuIds(new int[]{-1, 2, 3, 4}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** macOS 即便打开开关也应跳过绑定，不能因未配置 CPU 而让节点启动失败。 */
    @Test
    @EnabledOnOs(OS.MAC)
    void macSkipsEnabledAffinityEvenWithoutValidCpuAssignments() {
        // 打开开关但不提供有效编号，专门验证 macOS 的平台跳过分支。
        var configured = new TradingCpuAffinity(true, -1, -1, -1, -1);
        configured.validateConfiguration(LaneTopology.characterization());
        startsAndStopsOwner(configured);
        try (/* macOS 上 Owner 绑定也是空操作。 */ var owner = configured.owner();
             /* 不会尝试修改 Matcher 的 CPU 范围。 */ var matcher = configured.matcher(0);
             /* 不会尝试修改账户 Lane 0 的 CPU 范围。 */ var lane0 = configured.accountLane(0);
             /* 不会尝试修改账户 Lane 1 的 CPU 范围。 */ var lane1 = configured.accountLane(1)) {
            assertThat(Thread.currentThread().isAlive()).isTrue();
        }
    }

    /**
     * 用最小 Cluster 替身启动并停止真实 Owner，检查配置不会破坏工作线程生命周期。
     * @param configuration 本次要验证的 CPU 绑定配置
     */
    private static void startsAndStopsOwner(TradingCpuAffinity configuration) {
        // 只提供启动 Owner 必需的集群属性，意外调用其他接口会立即使测试失败。
        var cluster = (io.aeron.cluster.service.Cluster) java.lang.reflect.Proxy.newProxyInstance(
                io.aeron.cluster.service.Cluster.class.getClassLoader(),
                new Class<?>[]{io.aeron.cluster.service.Cluster.class},
                (/* 当前生成的代理实例；本替身不依赖它的其他状态。 */ proxy,
                 /* Owner 这次调用的 Cluster 方法，用名称选择固定测试返回值。 */ method,
                 /* 本次调用的参数；这里支持的查询方法都不需要读取参数。 */ args) -> switch (method.getName()) {
                    case "role" -> io.aeron.cluster.service.Cluster.Role.LEADER;
                    case "memberId" -> 0;
                    case "time", "logPosition" -> 0L;
                    case "idleStrategy" -> org.agrona.concurrent.NoOpIdleStrategy.INSTANCE;
                    default -> throw new AssertionError("unexpected Cluster API " + method.getName());
                });
        // 使用真实响应出口，与 Owner 共用同一组会话与角色状态。
        var egress = new com.surprising.aeron.support.egress.ClusterServiceEgress();
        // 启动真实 Owner 循环，检查 CPU 配置进入工作线程启动边界后仍然正确。
        var loop = new com.surprising.aeron.bootstrap.TradingOwnerLoop(
                com.surprising.product.api.ProductLine.SPOT, egress, configuration);
        egress.start(cluster);
        try {
            loop.start(cluster, null);
            loop.checkFailure();
        } finally { loop.terminate(); egress.clear(); }
    }

    /** Linux 上逐个绑定四种角色，并检查关闭凭据后是否准确恢复原先允许的 CPU 集合。 */
    @Test
    @EnabledOnOs(OS.LINUX)
    void linuxBindsEachRoleToTheRequestedCpuAndRestoresItsOriginalMask() {
        // 保存当前测试线程的 CPU 范围；任何断言失败也必须在 finally 中恢复。
        BitSet original = net.openhft.affinity.Affinity.getAffinity();
        org.junit.jupiter.api.Assumptions.assumeTrue(original.cardinality() >= 4,
                "four allowed CPUs are required for the four trading roles");
        // 从机器真正允许的 CPU 中取四个，避免依赖固定的部署机编号。
        int[] cpus = original.stream().limit(4).toArray();
        // 按 Owner、Matcher、Lane 0、Lane 1 顺序应用这四个有效 CPU。
        var configured = new TradingCpuAffinity(true, cpus[0], cpus[1], cpus[2], cpus[3]);
        configured.validateConfiguration(LaneTopology.productionDefault());
        try {
            for (/* 当前验证的角色下标，决定本轮应该绑定哪一个 CPU。 */ int role = 0; role < 4; role++) {
                try (/* 本轮绑定凭据；离开当前作用域就恢复进入前的 CPU 范围。 */ var binding = switch (role) {
                    case 0 -> configured.owner();
                    case 1 -> configured.matcher(0);
                    default -> configured.accountLane(role - 2);
                }) {
                    // 绑定期间只能保留当前角色指定的那一个 CPU。
                    BitSet expected = new BitSet();
                    expected.set(cpus[role]);
                    assertThat(net.openhft.affinity.Affinity.getAffinity()).isEqualTo(expected);
                }
                assertThat(net.openhft.affinity.Affinity.getAffinity()).isEqualTo(original);
            }
        } finally { net.openhft.affinity.Affinity.setAffinity(original); }
    }
}
