package com.surprising.aeron.bootstrap;

import com.surprising.aeron.config.ClusterTopology;
import com.surprising.aeron.config.CoreTransportConfiguration;
import com.surprising.aeron.config.CoreTransportConfiguration.ServiceIdleStrategy;
import com.surprising.aeron.controller.AeronTradingClusterService;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.exceptions.AeronException;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.NoOpLock;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 *  启动步骤 3–5：拥有单节点 Aeron 资源的创建、启动、等待与逆序关闭。配置绑定见 CoreConfiguration。
 * 日志： 记录节点启动、警告和致命错误，便于把故障定位到具体 Aeron 组件。
 * 构造约定：接收 Spring 已装配好的拓扑与业务服务，此时还没有启动网络和集群资源。
 * 参数 topology：本节点固定的地址与目录配置
 * 参数 clusteredService：负责把 Cluster 回调交给交易 Owner 的服务
 */
@Slf4j
@RequiredArgsConstructor
@Component
public final class AeronCoreLifecycle implements ApplicationListener<ContextClosedEvent>, DisposableBean {

    /** 当前节点的产品线、成员地址、端口和持久化目录。 */
    private final ClusterTopology topology;
    /** 接收复制日志和集群回调的交易服务，启动容器时交给 Aeron 托管。 */
    private final AeronTradingClusterService clusteredService;
    /** Spring 启动时已绑定并校验的网络和线程配置。 */
    private final CoreTransportConfiguration transport;
    /** 主线程等待的关闭信号；Spring 关闭和 Aeron 终止都唤醒同一个等待点。 */
    private final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();

    /**
     * 依次启动网络驱动、归档、共识模块和交易容器，然后等待关闭信号。
     * 正常退出时先关闭交易容器，再关闭底层驱动，避免业务还在使用已释放的网络内存。
     */
    @SuppressWarnings("try")
    public void startAndAwaitShutdown() {
        // 每个交易容器独立创建空闲等待策略；未配置时保留 Aeron 自己的默认策略。
        Supplier<IdleStrategy> serviceIdleStrategy = serviceIdleStrategySupplier(transport.service().idleStrategy());
        // 本成员自己的磁盘目录，归档和共识日志会继续放进不同子目录。
        File nodeDirectory = topology.nodeDirectory().toFile();
        // 本节点 MediaDriver 的共享内存目录名，不是磁盘日志的根目录。
        String aeronDirectoryName = topology.aeronDirectoryName();

        // 配置实际负责网络收发和共享缓冲区的驱动，并统一接入组件错误处理。
        MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
                .aeronDirectoryName(aeronDirectoryName)
                .threadingMode(transport.core().threadingMode())
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
                .localControlChannel(transport.archiveLocalControlChannel())
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
        // 共识模块负责选主、复制和命令排序，产品线和成员编号必须与拓扑一致。
        ConsensusModule.Context consensusContext = new ConsensusModule.Context()
                .clusterId(topology.clusterId())
                .clusterMemberId(topology.nodeId())
                .clusterMembers(topology.clusterMembers())
                .clusterDir(clusterDirectory)
                .ingressChannel(transport.clusterIngressChannel())
                .replicationChannel(topology.replicationChannel())
                .maxConcurrentSessions(transport.maxConcurrentSessions())
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

    /** 返回驱动判定客户端失去活跃状态的超时时间，单位为纳秒，当前为 30 秒。 */
    static long coreClientLivenessTimeoutNs() {
        return TimeUnit.SECONDS.toNanos(30);
    }

    /** 每个交易容器独立创建可变等待策略，null 保留 Aeron 自己的默认工厂。 */
    static Supplier<IdleStrategy> serviceIdleStrategySupplier(ServiceIdleStrategy strategy) {
        if (strategy == null) return null;
        return switch (strategy) {
            case BACKOFF -> () -> io.aeron.driver.Configuration.agentIdleStrategy("backoff", null);
            case YIELDING -> YieldingIdleStrategy::new;
        };
    }

    /** 返回 Publication 阻塞后允许驱动尝试解阻的时间，单位为纳秒，当前为 60 秒。 */
    static long corePublicationUnblockTimeoutNs() {
        return TimeUnit.SECONDS.toNanos(60);
    }

}
