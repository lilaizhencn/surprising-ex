package com.surprising.aeron.config;

import com.surprising.product.api.ProductLine;
import java.nio.file.Path;
import java.util.Arrays;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

/** 接收 surprising.aeron 下的启动配置，并把它们组装成一个节点可直接使用的配置对象。 */
@Getter
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CoreRuntimeConfiguration.class, CoreTransportConfiguration.class, RealtimeConfiguration.class})
@ConfigurationProperties(prefix = "surprising.aeron")
public class CoreConfiguration {

    /** 首次启动使用的 Matcher 数量，恢复时由快照保存的拓扑决定。 */
    @Setter
    private int matchingEngines = LaneTopology.DEFAULT_MATCHING_ENGINE_COUNT;
    @Setter
    private int accountLanes = LaneTopology.DEFAULT_ACCOUNT_LANE_COUNT;
    @Setter
    private long accountLaneSeed = LaneTopology.DEFAULT_ACCOUNT_LANE_SEED;
    @Setter
    private int matcherWindowSize = LaneTopology.DEFAULT_MATCHER_WINDOW_SIZE;
    @Setter
    private int matchingCompletionCapacity = LaneTopology.DEFAULT_QUEUE_CAPACITY;
    @Setter
    private int accountLaneQueueCapacity = LaneTopology.DEFAULT_QUEUE_CAPACITY;

    /** 从已绑定的配置创建并校验启动拓扑，Owner、账户和 Matcher 共用该值。 */
    @Bean
    public LaneTopology laneTopology() {
        return createLaneTopology();
    }

    private LaneTopology createLaneTopology() {
        return new LaneTopology(matchingEngines, accountLanes, accountLaneSeed, matcherWindowSize,
                matchingCompletionCapacity, accountLaneQueueCapacity);
    }

    /** 非 Spring 的工具和基准入口使用相同配置字段与 Binder，读取系统属性及环境变量。 */
    public static LaneTopology standaloneLaneTopology() {
        return Binder.get(new StandardEnvironment())
                .bind("surprising.aeron", Bindable.of(CoreConfiguration.class))
                .orElseGet(CoreConfiguration::new).createLaneTopology();
    }

    /** 先接收配置值，真正绑定线程时还会检查平台和可用 CPU。 */
    private final CpuAffinity cpuAffinity = new CpuAffinity();

    /** 四个交易线程的 CPU 配置。 */
    @Setter
    @Getter
    public static final class CpuAffinity {
        /** 默认关闭，避免没有部署配置就改变线程的运行位置。 */
        private boolean enabled;
        /** 交易 Owner 要使用的操作系统 CPU 编号。返回交易 Owner 配置的 CPU 编号。接收交易 Owner 的 CPU 配置，不在配置绑定阶段操作线程。 */
        private int ownerCpu = -1;
        /** 唯一 Matcher 工作线程要使用的 CPU 编号。 */
        private int matcherCpu = -1;
        private int lane0Cpu = -1;
        private int lane1Cpu = -1;

    }

    /** 生成只读的 CPU 绑定规则，后续由各交易线程在自己的线程内申请和恢复绑定。 */
    @Bean
    public TradingCpuAffinity tradingCpuAffinity() {
        return new TradingCpuAffinity(cpuAffinity.enabled, cpuAffinity.ownerCpu,
                cpuAffinity.matcherCpu, cpuAffinity.lane0Cpu, cpuAffinity.lane1Cpu);
    }

    /** 必须显式配置，防止误连到另一条产品线的集群。 */
    private String productLine;
    @Setter
    private int nodeId;
    private String hostnames = "localhost,localhost,localhost";
    private Path dataDir = Path.of("data/aeron");

    /** 接收产品线代码，去掉首尾空格。 */
    public void setProductLine(String productLine) {
        this.productLine = productLine == null ? null : productLine.trim();
    }

    /** 接收成员地址。 */
    public void setHostnames(String hostnames) {
        if (hostnames != null && !hostnames.isBlank()) {
            this.hostnames = hostnames.trim();
        }
    }

    /** 接收持久化根目录。 */
    public void setDataDir(Path dataDir) {
        if (dataDir != null) {
            this.dataDir = dataDir;
        }
    }

    /** 检查必填产品线，拆分成员地址，生成供网络和持久化资源共用的节点拓扑。配置错误会让启动失败，避免带着错误地址继续运行。 */
    @Bean
    public ClusterTopology clusterTopology() {
        if (productLine == null || productLine.isBlank()) {
            throw new IllegalStateException("surprising.aeron.product-line is required");
        }
        return new ClusterTopology(ProductLine.requireExternalCode(productLine), nodeId,
                Arrays.stream(hostnames.split(",")).map(String::trim).toList(), dataDir);
    }

}
