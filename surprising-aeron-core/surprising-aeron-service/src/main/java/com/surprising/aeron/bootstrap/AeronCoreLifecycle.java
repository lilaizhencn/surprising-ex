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

/** 启动步骤 3–5：拥有单节点 Aeron 资源的创建、启动、等待与逆序关闭。参数 topology：本节点固定的地址与目录配置 参数 clusteredService：负责把 Cluster 回调交给交易 Owner 的服务。 */
@Slf4j
@RequiredArgsConstructor
@Component
public final class AeronCoreLifecycle implements ApplicationListener<ContextClosedEvent>, DisposableBean {

    private final ClusterTopology topology;
    private final AeronTradingClusterService clusteredService;
    /** Spring 启动时已绑定并校验的网络和线程配置。 */
    private final CoreTransportConfiguration transport;
    /** 主线程等待的关闭信号。 */
    private final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();

    /** 依次启动网络驱动、归档、共识模块和交易容器，然后等待关闭信号。正常退出时先关闭交易容器，再关闭底层驱动，避免业务还在使用已释放的网络内存。 */
    @SuppressWarnings("try")
    public void startAndAwaitShutdown() {
        Supplier<IdleStrategy> serviceIdleStrategy = serviceIdleStrategySupplier(transport.service().idleStrategy());
        File nodeDirectory = topology.nodeDirectory().toFile();
        String aeronDirectoryName = topology.aeronDirectoryName();

        MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
                .aeronDirectoryName(aeronDirectoryName)
                .threadingMode(transport.core().threadingMode())
                .clientLivenessTimeoutNs(coreClientLivenessTimeoutNs())
                .publicationUnblockTimeoutNs(corePublicationUnblockTimeoutNs())
                .termBufferSparseFile(false)
                .errorHandler(errorHandler("media-driver"));

        AeronArchive.Context replicationArchiveContext = new AeronArchive.Context()
                .controlResponseChannel("aeron:udp?endpoint=" + topology.hostname() + ":0");
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

        AeronArchive.Context localArchiveClient = new AeronArchive.Context()
                .lock(NoOpLock.INSTANCE)
                .controlRequestChannel(archiveContext.localControlChannel())
                .controlResponseChannel(archiveContext.localControlChannel())
                .aeronDirectoryName(aeronDirectoryName);

        File clusterDirectory = new File(nodeDirectory, "cluster");
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

        try (
                ClusteredMediaDriver ignored = ClusteredMediaDriver.launch(
                    mediaDriverContext.terminationHook(barrier::signalAll),
                    archiveContext,
                    consensusContext.terminationHook(barrier::signalAll))) {
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
            try (
                    ClusteredServiceContainer ignoredContainer = ClusteredServiceContainer.launch(
                    serviceContext.terminationHook(barrier::signalAll))) {
                log.info("Aeron core started productLine={} nodeId={} clusterId={} host={}",
                        topology.productLine(), topology.nodeId(), topology.clusterId(), topology.hostname());
                barrier.await();
            }
        }
    }

    /** Spring 上下文关闭时唤醒启动线程，让它按资源层次退出。 */
    @Override
    public void onApplicationEvent(@NonNull ContextClosedEvent event) {
        barrier.signalAll();
    }

    /** Spring 销毁组件时释放关闭信号屏障本身占用的资源。 */
    @Override
    public void destroy() {
        barrier.close();
    }

    /** 创建生产使用的错误处理器，遇到致命错误时立即结束进程，交给外部监督程序恢复节点。 */
    private static ErrorHandler errorHandler(String component) {
        return errorHandler(component, status -> Runtime.getRuntime().halt(status));
    }

    /** 区分可继续运行的警告与必须停止的故障。终止动作可由测试替换，避免测试真的结束 JVM。 */
    static ErrorHandler errorHandler(String component, java.util.function.IntConsumer terminate) {
        return throwable -> {
            if (throwable instanceof AeronException aeronException
                    && aeronException.category() == AeronException.Category.WARN) {
                log.warn("Aeron {} warning", component, aeronException);
                return;
            }
            log.error("Aeron {} failure", component, throwable);
            if (throwable instanceof AeronException aeronException
                    && aeronException.category() == AeronException.Category.FATAL
                    || throwable instanceof org.agrona.concurrent.AgentTerminationException
                    || throwable instanceof Error) {
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
