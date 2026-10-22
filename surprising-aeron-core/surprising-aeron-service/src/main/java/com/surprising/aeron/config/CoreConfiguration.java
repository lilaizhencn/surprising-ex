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

/**
 * 接收 surprising.aeron 下的启动配置，并把它们组装成一个节点可直接使用的配置对象。
 * 统一绑定节点、交易分片、队列与 CPU 配置，不加载订单或账户数据。
 */
@Getter
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "surprising.aeron")
public class CoreConfiguration {

    /** 首次启动使用的 Matcher 数量，恢复时由快照保存的拓扑决定。
     * 返回首次启动的 Matcher 数量。
     */
    @Setter
    private int matchingEngines = LaneTopology.DEFAULT_MATCHING_ENGINE_COUNT;
    /** 原生风险引擎数量，不表示启动同数量的账户风控线程。
     * 返回原生风险引擎数量。
     */
    @Setter
    private int riskEngines = LaneTopology.DEFAULT_RISK_ENGINE_COUNT;
    /** 首次启动时按用户划分的账户 Lane 数量。
     * 返回首次启动的账户 Lane 数量。
     */
    @Setter
    private int accountLanes = LaneTopology.DEFAULT_ACCOUNT_LANE_COUNT;
    /** 固定用户到账户 Lane 映射的种子，随快照保存。
     * 返回固定用户路由使用的种子。
     */
    @Setter
    private long accountLaneSeed = LaneTopology.DEFAULT_ACCOUNT_LANE_SEED;
    /** Matcher 可同时登记的在途命令槽位数。
     * 返回 Matcher 在途窗口大小。
     */
    @Setter
    private int matcherWindowSize = LaneTopology.DEFAULT_MATCHER_WINDOW_SIZE;
    /** Matcher 完成队列容量。
     * 返回 Matcher 完成队列容量。
     */
    @Setter
    private int matchingCompletionCapacity = LaneTopology.DEFAULT_QUEUE_CAPACITY;
    /** 每个账户 Lane 的输入队列容量。
     */
    @Setter
    private int accountLaneQueueCapacity = LaneTopology.DEFAULT_QUEUE_CAPACITY;

    /** 从已绑定的配置创建并校验启动拓扑，Owner、账户和 Matcher 共用该值。 */
    @Bean
    public LaneTopology laneTopology() {
        return toLaneTopology();
    }

    /** 将绑定值转换成已校验的不可变拓扑，不依赖 Spring Bean 生命周期。 */
    public LaneTopology toLaneTopology() {
        return new LaneTopology(matchingEngines, riskEngines, matchingEngines - 1,
                accountLanes, accountLaneSeed, matcherWindowSize,
                matchingCompletionCapacity, accountLaneQueueCapacity);
    }

    /**
     * 非 Spring 的工具和基准入口使用相同配置字段与 Binder，读取系统属性及环境变量；
     * 不启动应用上下文，也不隐式加载节点的 YAML。Spring 节点直接注入已绑定的拓扑 Bean。
     */
    public static LaneTopology standaloneLaneTopology() {
        return Binder.get(new StandardEnvironment())
                .bind("surprising.aeron", Bindable.of(CoreConfiguration.class))
                .orElseGet(CoreConfiguration::new).toLaneTopology();
    }

    /** CPU 绑定配置；先接收配置值，真正绑定线程时还会检查平台和可用 CPU。
     * -- GETTER --
     * 返回需要绑定的 CPU 编号及开关，供 Spring 填充 cpu-affinity 下的配置。
     */
    private final CpuAffinity cpuAffinity = new CpuAffinity();

    /** 四个交易线程的 CPU 配置；-1 表示还没有指定 CPU，不表示自动分配。 */
    @Setter
    @Getter
    public static final class CpuAffinity {
        /** 是否要求绑定 CPU；默认关闭，避免没有部署配置就改变线程的运行位置。
         * 返回是否要求启用 CPU 绑定；是否能绑定还要由运行平台检查决定。
         */
        private boolean enabled;
        /** 交易 Owner 要使用的操作系统 CPU 编号。
         * 返回交易 Owner 配置的 CPU 编号。
         *  接收交易 Owner 的 CPU 配置，不在配置绑定阶段操作线程。
         */
        private int ownerCpu = -1;
        /** 唯一 Matcher 工作线程要使用的 CPU 编号。
         */
        private int matcherCpu = -1;
        /** 账户 Lane 0 要使用的 CPU 编号。
         */
        private int lane0Cpu = -1;
        /** 账户 Lane 1 要使用的 CPU 编号。
         */
        private int lane1Cpu = -1;

    }

    /** 生成只读的 CPU 绑定规则，后续由各交易线程在自己的线程内申请和恢复绑定。 */
    @Bean
    public TradingCpuAffinity tradingCpuAffinity() {
        return new TradingCpuAffinity(cpuAffinity.enabled, cpuAffinity.ownerCpu,
                cpuAffinity.matcherCpu, cpuAffinity.lane0Cpu, cpuAffinity.lane1Cpu);
    }

    /** 本节点所属产品线；必须显式配置，防止误连到另一条产品线的集群。
     * -- GETTER --
     * 返回用户配置的产品线代码，尚未转换成产品线枚举。
     */
    private String productLine;
    /** 本节点在成员列表中的下标，从 0 开始。
     * 返回本节点在成员列表中的编号。
     *  接收节点编号，是否在成员列表范围内由拓扑对象检查。
     */
    @Setter
    private int nodeId;
    /** 按成员编号排列的主机地址，以逗号分隔；本地单成员运行时可只配置一个地址。
     */
    private String hostnames = "localhost,localhost,localhost";
    /** 集群数据的根目录；实际还会在下面分出产品线和节点目录。
     * -- GETTER --
     * 返回所有产品线节点数据共用的根目录，而不是某一个节点的最终目录。
     */
    private Path dataDir = Path.of("data/aeron");

    /**
     * 接收产品线代码，去掉首尾空格；缺失或非法的值在创建拓扑时拒绝。
     * @param productLine 产品线代码；未配置时可以为 null
     */
    public void setProductLine(String productLine) {
        this.productLine = productLine == null ? null : productLine.trim();
    }

    /**
     * 接收成员地址；空配置不覆盖当前值，每个地址的空格在创建拓扑时再去掉。
     * @param hostnames 按成员编号排列、用逗号分隔的主机地址
     */
    public void setHostnames(String hostnames) {
        if (hostnames != null && !hostnames.isBlank()) {
            this.hostnames = hostnames.trim();
        }
    }

    /**
     * 接收持久化根目录；null 不覆盖已有配置。
     * @param dataDir 保存日志、归档和快照的根目录
     */
    public void setDataDir(Path dataDir) {
        if (dataDir != null) {
            this.dataDir = dataDir;
        }
    }

    /**
     * 检查必填产品线，拆分成员地址，生成供网络和持久化资源共用的节点拓扑。
     * 配置错误会让启动失败，避免带着错误地址继续运行。
     */
    @Bean
    public ClusterTopology clusterTopology() {
        if (productLine == null || productLine.isBlank()) {
            throw new IllegalStateException("surprising.aeron.product-line is required");
        }
        return new ClusterTopology(ProductLine.requireExternalCode(productLine), nodeId,
                Arrays.stream(hostnames.split(",")).map(String::trim).toList(), dataDir);
    }

}
