package com.surprising.aeron.service.bootstrap;

import lombok.extern.slf4j.Slf4j;

import com.surprising.aeron.service.cluster.ClusterTopology;
import com.surprising.aeron.service.cluster.AeronTradingClusterService;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.exceptions.AeronException;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.NoOpLock;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.DisposableBean;

/** 启动步骤 3–5：拥有单节点 Aeron 资源的创建、启动、等待与逆序关闭。配置绑定见 CoreConfiguration。 */
@Component
public final class AeronCoreLifecycle implements ApplicationListener<ContextClosedEvent>, DisposableBean {

    /** 记录节点启动、警告和致命错误，便于把故障定位到具体 Aeron 组件。 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AeronCoreLifecycle.class);


    /** 当前节点的产品线、成员地址、端口和持久化目录。 */
    private final ClusterTopology topology;
    /** 接收复制日志和集群回调的交易服务，启动容器时交给 Aeron 托管。 */
    private final AeronTradingClusterService clusteredService;
    /** 主线程等待的关闭信号；Spring 关闭和 Aeron 终止都唤醒同一个等待点。 */
    private final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();

    /**
     * 接收 Spring 已装配好的拓扑与业务服务，此时还没有启动网络和集群资源。
     * @param topology 本节点固定的地址与目录配置
     * @param clusteredService 负责把 Cluster 回调交给交易 Owner 的服务
     */
    public AeronCoreLifecycle(ClusterTopology topology, AeronTradingClusterService clusteredService) {
        this.topology = topology;
        this.clusteredService = clusteredService;
    }

    /**
     * 依次启动网络驱动、归档、共识模块和交易容器，然后等待关闭信号。
     * 正常退出时先关闭交易容器，再关闭底层驱动，避免业务还在使用已释放的网络内存。
     */
    @SuppressWarnings("try")
    public void startAndAwaitShutdown() {
        // 每个交易容器独立创建空闲等待策略；未配置时保留 Aeron 自己的默认策略。
        Supplier<IdleStrategy> serviceIdleStrategy = serviceIdleStrategySupplier();
        // 本成员自己的磁盘目录，归档和共识日志会继续放进不同子目录。
        File nodeDirectory = topology.nodeDirectory().toFile();
        // 本节点 MediaDriver 的共享内存目录名，不是磁盘日志的根目录。
        String aeronDirectoryName = topology.aeronDirectoryName();

        // 配置实际负责网络收发和共享缓冲区的驱动，并统一接入组件错误处理。
        MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
                .aeronDirectoryName(aeronDirectoryName)
                .threadingMode(coreThreadingMode())
                .clientLivenessTimeoutNs(coreClientLivenessTimeoutNs())
                .publicationUnblockTimeoutNs(corePublicationUnblockTimeoutNs())
                .termBufferSparseFile(false)
                .errorHandler(errorHandler("media-driver"));

        // 供 Archive 复制交互使用的客户端配置，响应端口由操作系统分配。
        AeronArchive.Context replicationArchiveContext = new AeronArchive.Context()
                .controlResponseChannel("aeron:udp?endpoint=" + topology.hostname() + ":0");
        // 保存集群录制数据；录制和回放都校验 CRC32C，归档代理共用一条工作线程。
        Archive.Context archiveContext = new Archive.Context()
                .aeronDirectoryName(aeronDirectoryName)
                .archiveDir(new File(nodeDirectory, "archive"))
                .controlChannel(topology.archiveControlChannel())
                .archiveClientContext(replicationArchiveContext)
                .localControlChannel(localArchiveControlChannel())
                .recordingEventsEnabled(false)
                .recordChecksum(io.aeron.archive.checksum.Checksums.crc32c())
                .replayChecksum(io.aeron.archive.checksum.Checksums.crc32c())
                .threadingMode(ArchiveThreadingMode.SHARED)
                .replicationChannel(topology.replicationChannel());

        // Consensus 和交易容器通过本地 IPC 访问 Archive，分别克隆配置而不共用可变上下文。
        AeronArchive.Context localArchiveClient = new AeronArchive.Context()
                .lock(NoOpLock.INSTANCE)
                .controlRequestChannel(archiveContext.localControlChannel())
                .controlResponseChannel(archiveContext.localControlChannel())
                .aeronDirectoryName(aeronDirectoryName);

        // 共识日志与集群元数据的子目录，与 Archive 录制目录分开。
        File clusterDirectory = new File(nodeDirectory, "cluster");
        // 同时允许存在的客户端会话上限，不是单个会话的在途命令数量。
        int maxConcurrentSessions = Integer.getInteger("surprising.aeron.max-concurrent-sessions", 64);
        if (maxConcurrentSessions < 1 || maxConcurrentSessions > 1_024) {
            throw new IllegalArgumentException("surprising.aeron.max-concurrent-sessions must be in [1,1024]");
        }
        // 共识模块负责选主、复制和命令排序，产品线和成员编号必须与拓扑一致。
        ConsensusModule.Context consensusContext = new ConsensusModule.Context()
                .clusterId(topology.clusterId())
                .clusterMemberId(topology.nodeId())
                .clusterMembers(topology.clusterMembers())
                .clusterDir(clusterDirectory)
                .ingressChannel(clusterIngressChannel())
                .replicationChannel(topology.replicationChannel())
                .maxConcurrentSessions(maxConcurrentSessions)
                .archiveContext(localArchiveClient.clone())
                .errorHandler(errorHandler("consensus-module"));

        try (/* 持有 Driver、Archive 和 Consensus；离开外层作用域时统一关闭。 */
                ClusteredMediaDriver ignored = ClusteredMediaDriver.launch(
                    mediaDriverContext.terminationHook(barrier::signalAll),
                    archiveContext,
                    consensusContext.terminationHook(barrier::signalAll))) {
            // 把交易服务绑定到同一个集群和 Archive，由容器线程接收生命周期回调。
            ClusteredServiceContainer.Context serviceContext = new ClusteredServiceContainer.Context()
                    .clusterId(topology.clusterId())
                    .aeronDirectoryName(aeronDirectoryName)
                    .archiveContext(localArchiveClient.clone())
                    .clusterDir(clusterDirectory)
                    .clusteredService(clusteredService)
                    .errorHandler(errorHandler("clustered-service"));
            if (serviceIdleStrategy != null) {
                serviceContext.idleStrategySupplier(serviceIdleStrategy);
            }
            try (/* 必须先于底层驱动关闭的交易服务容器。 */
                    ClusteredServiceContainer ignoredContainer = ClusteredServiceContainer.launch(
                    serviceContext.terminationHook(barrier::signalAll))) {
                log.info("Aeron core started productLine={} nodeId={} clusterId={} host={}",
                        topology.productLine(), topology.nodeId(), topology.clusterId(), topology.hostname());
                barrier.await();
            }
        }
    }

    /**
     * Spring 上下文关闭时唤醒启动线程，让它按资源层次退出。
     * @param event 本次 Spring 上下文的关闭事件；这里只需要知道关闭已发生
     */
    @Override
    public void onApplicationEvent(@NonNull ContextClosedEvent event) {
        barrier.signalAll();
    }

    /** Spring 销毁组件时释放关闭信号屏障本身占用的资源。 */
    @Override
    public void destroy() {
        barrier.close();
    }

    /**
     * 创建生产使用的错误处理器，遇到致命错误时立即结束进程，交给外部监督程序恢复节点。
     * @param component 发生错误的 Aeron 组件名称，用于日志定位
     */
    private static ErrorHandler errorHandler(String component) {
        return errorHandler(component, /* 致命故障要使用的进程退出码。 */ status -> Runtime.getRuntime().halt(status));
    }

    /**
     * 区分可继续运行的警告与必须停止的故障；终止动作可由测试替换，避免测试真的结束 JVM。
     * @param component 发生错误的组件名称
     * @param terminate 接收退出码的终止动作，只有致命错误才调用
     */
    static ErrorHandler errorHandler(String component, java.util.function.IntConsumer terminate) {
        return /* 当前组件交上来的错误，先检查警告级别，再决定是否结束进程。 */ throwable -> {
            if (throwable instanceof AeronException /* 带有严重程度分类的 Aeron 错误。 */ aeronException
                    && aeronException.category() == AeronException.Category.WARN) {
                log.warn("Aeron {} warning", component, aeronException);
                return;
            }
            log.error("Aeron {} failure", component, throwable);
            if (throwable instanceof AeronException /* 用分类判断是否属于不能继续运行的致命错误。 */ aeronException
                    && aeronException.category() == AeronException.Category.FATAL
                    || throwable instanceof org.agrona.concurrent.AgentTerminationException
                    || throwable instanceof Error) {
                // 致命错误可能使共享内存映射失效，继续运行或做普通关闭都可能再次访问失效内存。
                // 直接停止本成员，由外部监督程序根据已提交日志和快照重新启动。
                terminate.accept(1);
            }
        };
    }

    /** 依次读取 JVM 属性、环境变量和 CPU 数量默认值，决定 MediaDriver 如何安排工作线程。 */
    static ThreadingMode coreThreadingMode() {
        // 启动参数优先；只有没有显式设置时才读取环境变量或根据机器规模选择默认模式。
        String configured = System.getProperty("surprising.aeron.core.threading-mode");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv().get("AERON_CORE_THREADING_MODE");
        }
        if (configured == null || configured.isBlank()) {
            configured = Runtime.getRuntime().availableProcessors() >= 12 ? "DEDICATED" : "SHARED_NETWORK";
        }
        try {
            return ThreadingMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (/* 保留非法模式的原始原因，并在新错误中指出具体配置项。 */ IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "surprising.aeron.core.threading-mode must be a valid Aeron ThreadingMode: " + configured,
                    exception);
        }
    }

    /** 返回驱动判定客户端失去活跃状态的超时时间，单位为纳秒，当前为 30 秒。 */
    static long coreClientLivenessTimeoutNs() {
        return TimeUnit.SECONDS.toNanos(30);
    }

    /** 读取交易服务专用的空闲策略配置，不改变 Consensus 或其他 Aeron 组件的全局配置。 */
    static Supplier<IdleStrategy> serviceIdleStrategySupplier() {
        // JVM 属性优先，空值时才读取环境变量；具体名称校验交给另一个重载处理。
        String configured = System.getProperty("surprising.aeron.service.idle-strategy");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv().get("AERON_SERVICE_IDLE_STRATEGY");
        }
        return serviceIdleStrategySupplier(configured);
    }

    /**
     * 把配置名称转换成策略工厂，保证每个容器拿到自己的可变策略实例。
     * @param configured 空值表示沿用 Aeron 默认值，否则只接受 BACKOFF 或 YIELDING
     * @return 创建策略的工厂；null 表示不覆盖 Aeron 的默认策略
     */
    static Supplier<IdleStrategy> serviceIdleStrategySupplier(String configured) {
        // 未配置时不强行设置策略，保留 Aeron 自带的工厂和全局属性行为。
        if (configured == null || configured.isBlank()) return null;
        return switch (configured.trim().toUpperCase(Locale.ROOT)) {
            case "BACKOFF" -> () -> io.aeron.driver.Configuration.agentIdleStrategy("backoff", null);
            case "YIELDING" -> YieldingIdleStrategy::new;
            default -> throw new IllegalArgumentException(
                    "surprising.aeron.service.idle-strategy must be BACKOFF or YIELDING: " + configured);
        };
    }

    /** 返回 Publication 阻塞后允许驱动尝试解阻的时间，单位为纳秒，当前为 60 秒。 */
    static long corePublicationUnblockTimeoutNs() {
        return TimeUnit.SECONDS.toNanos(60);
    }

    /** 读取接收客户端命令的 Cluster ingress 通道，未配置时使用默认 UDP 通道。 */
    static String clusterIngressChannel() {
        return configuredChannel("surprising.aeron.cluster-ingress-channel",
                "AERON_CLUSTER_INGRESS_CHANNEL", "aeron:udp?term-length=16m");
    }

    /** 读取节点内部访问 Archive 的控制通道，未配置时使用 IPC。 */
    static String localArchiveControlChannel() {
        return configuredChannel("surprising.aeron.archive-local-control-channel",
                "AERON_ARCHIVE_LOCAL_CONTROL_CHANNEL", "aeron:ipc?term-length=1m");
    }

    /**
     * 按 JVM 属性、环境变量、默认值的优先顺序读取通道，并拒绝明显不是 Aeron URI 的值。
     * @param property JVM 系统属性名称
     * @param environment 对应的环境变量名称
     * @param defaultValue 两种来源都未提供时采用的通道 URI
     */
    private static String configuredChannel(String property, String environment, String defaultValue) {
        // 只保存本次解析到的通道配置，去空格后再检查协议前缀。
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        if (value == null || value.isBlank()) value = defaultValue;
        value = value.trim();
        if (!value.startsWith("aeron:")) {
            throw new IllegalArgumentException(property + " must be an Aeron channel URI");
        }
        return value;
    }
}
