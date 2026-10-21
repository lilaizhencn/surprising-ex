package com.surprising.aeron.service.config;

import com.surprising.aeron.service.cluster.ClusterTopology;
import com.surprising.product.api.ProductLine;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接收 surprising.aeron 下的启动配置，并把它们组装成一个节点可直接使用的配置对象。
 * 这里只描述节点属于哪条产品线、连接哪些成员以及如何绑定 CPU，不加载订单或账户数据。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoreConfiguration.class)
@ConfigurationProperties(prefix = "surprising.aeron")
public class CoreConfiguration {

    /** CPU 绑定配置；先接收配置值，真正绑定线程时还会检查平台和可用 CPU。 */
    private final CpuAffinity cpuAffinity = new CpuAffinity();

    /** 返回需要绑定的 CPU 编号及开关，供 Spring 填充 cpu-affinity 下的配置。 */
    public CpuAffinity getCpuAffinity() { return cpuAffinity; }

    /** 四个交易线程的 CPU 配置；-1 表示还没有指定 CPU，不表示自动分配。 */
    public static final class CpuAffinity {
        /** 是否要求绑定 CPU；默认关闭，避免没有部署配置就改变线程的运行位置。 */
        private boolean enabled;
        /** 交易 Owner 要使用的操作系统 CPU 编号。 */
        private int ownerCpu = -1;
        /** 唯一 Matcher 工作线程要使用的 CPU 编号。 */
        private int matcherCpu = -1;
        /** 账户 Lane 0 要使用的 CPU 编号。 */
        private int lane0Cpu = -1;
        /** 账户 Lane 1 要使用的 CPU 编号。 */
        private int lane1Cpu = -1;

        /** 返回是否要求启用 CPU 绑定；是否能绑定还要由运行平台检查决定。 */
        public boolean isEnabled() { return enabled; }
        /**
         * 接收是否启用 CPU 绑定的配置。
         * @param enabled true 表示要求绑定，false 表示保持系统默认调度
         */
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        /** 返回交易 Owner 配置的 CPU 编号。 */
        public int getOwnerCpu() { return ownerCpu; }
        /**
         * 接收交易 Owner 的 CPU 配置，不在配置绑定阶段操作线程。
         * @param cpu 操作系统 CPU 编号，未配置时为 -1
         */
        public void setOwnerCpu(int cpu) { ownerCpu = cpu; }
        /** 返回 Matcher 配置的 CPU 编号。 */
        public int getMatcherCpu() { return matcherCpu; }
        /**
         * 接收 Matcher 的 CPU 配置。
         * @param cpu 操作系统 CPU 编号，未配置时为 -1
         */
        public void setMatcherCpu(int cpu) { matcherCpu = cpu; }
        /** 返回账户 Lane 0 配置的 CPU 编号。 */
        public int getLane0Cpu() { return lane0Cpu; }
        /**
         * 接收账户 Lane 0 的 CPU 配置。
         * @param cpu 操作系统 CPU 编号，未配置时为 -1
         */
        public void setLane0Cpu(int cpu) { lane0Cpu = cpu; }
        /** 返回账户 Lane 1 配置的 CPU 编号。 */
        public int getLane1Cpu() { return lane1Cpu; }
        /**
         * 接收账户 Lane 1 的 CPU 配置。
         * @param cpu 操作系统 CPU 编号，未配置时为 -1
         */
        public void setLane1Cpu(int cpu) { lane1Cpu = cpu; }
    }

    /** 生成只读的 CPU 绑定规则，后续由各交易线程在自己的线程内申请和恢复绑定。 */
    @Bean
    public TradingCpuAffinity tradingCpuAffinity() {
        return new TradingCpuAffinity(cpuAffinity.enabled, cpuAffinity.ownerCpu,
                cpuAffinity.matcherCpu, cpuAffinity.lane0Cpu, cpuAffinity.lane1Cpu);
    }

    /** 本节点所属产品线；必须显式配置，防止误连到另一条产品线的集群。 */
    private String productLine;
    /** 本节点在成员列表中的下标，从 0 开始。 */
    private int nodeId;
    /** 按成员编号排列的主机地址，以逗号分隔；本地单成员运行时可只配置一个地址。 */
    private String hostnames = "localhost,localhost,localhost";
    /** 集群数据的根目录；实际还会在下面分出产品线和节点目录。 */
    private Path dataDir = Path.of("data/aeron");

    /** 返回用户配置的产品线代码，尚未转换成产品线枚举。 */
    public String getProductLine() {
        return productLine;
    }

    /**
     * 接收产品线代码，去掉首尾空格；缺失或非法的值在创建拓扑时拒绝。
     * @param productLine 产品线代码；未配置时可以为 null
     */
    public void setProductLine(String productLine) {
        this.productLine = productLine == null ? null : productLine.trim();
    }

    /** 返回本节点在成员列表中的编号。 */
    public int getNodeId() {
        return nodeId;
    }

    /**
     * 接收节点编号，是否在成员列表范围内由拓扑对象检查。
     * @param nodeId 从 0 开始的成员下标
     */
    public void setNodeId(int nodeId) {
        this.nodeId = nodeId;
    }

    /** 返回还没有拆分成列表的集群主机配置。 */
    public String getHostnames() {
        return hostnames;
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

    /** 返回所有产品线节点数据共用的根目录，而不是某一个节点的最终目录。 */
    public Path getDataDir() {
        return dataDir;
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
