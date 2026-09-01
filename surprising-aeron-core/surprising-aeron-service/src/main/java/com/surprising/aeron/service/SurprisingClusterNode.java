package com.surprising.aeron.service;

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
import org.agrona.ErrorHandler;
import org.agrona.concurrent.NoOpLock;
import org.agrona.concurrent.ShutdownSignalBarrier;

public final class SurprisingClusterNode {

    private SurprisingClusterNode() {
    }

    @SuppressWarnings("try")
    public static void main(String[] args) {
        ClusterTopology topology = ClusterTopology.fromSystemProperties();
        File nodeDirectory = topology.nodeDirectory().toFile();
        String aeronDirectoryName = topology.aeronDirectoryName();
        ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();

        MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
                .aeronDirectoryName(aeronDirectoryName)
                .threadingMode(coreThreadingMode())
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
                .localControlChannel(localArchiveControlChannel())
                .recordingEventsEnabled(false)
                .threadingMode(ArchiveThreadingMode.SHARED)
                .replicationChannel(topology.replicationChannel());

        AeronArchive.Context localArchiveClient = new AeronArchive.Context()
                .lock(NoOpLock.INSTANCE)
                .controlRequestChannel(archiveContext.localControlChannel())
                .controlResponseChannel(archiveContext.localControlChannel())
                .aeronDirectoryName(aeronDirectoryName);

        File clusterDirectory = new File(nodeDirectory, "cluster");
        int maxConcurrentSessions = Integer.getInteger("surprising.aeron.max-concurrent-sessions", 64);
        if (maxConcurrentSessions < 1 || maxConcurrentSessions > 1_024) {
            throw new IllegalArgumentException("surprising.aeron.max-concurrent-sessions must be in [1,1024]");
        }
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

        try (ClusteredMediaDriver ignored = ClusteredMediaDriver.launch(
                    mediaDriverContext.terminationHook(barrier::signalAll),
                    archiveContext,
                    consensusContext.terminationHook(barrier::signalAll))) {
            ClusteredServiceContainer.Context serviceContext = new ClusteredServiceContainer.Context()
                    .clusterId(topology.clusterId())
                    .aeronDirectoryName(aeronDirectoryName)
                    .archiveContext(localArchiveClient.clone())
                    .clusterDir(clusterDirectory)
                    .clusteredService(new SurprisingClusteredService(topology.productLine()))
                    .errorHandler(errorHandler("clustered-service"));
            try (ClusteredServiceContainer ignoredContainer = ClusteredServiceContainer.launch(
                    serviceContext.terminationHook(barrier::signalAll))) {
                System.out.printf("Aeron core started productLine=%s nodeId=%d clusterId=%d host=%s%n",
                        topology.productLine(), topology.nodeId(), topology.clusterId(), topology.hostname());
                barrier.await();
            }
        } finally {
            barrier.close();
        }
    }

    private static ErrorHandler errorHandler(String component) {
        return throwable -> {
            if (throwable instanceof AeronException aeronException
                    && aeronException.category() == AeronException.Category.WARN) {
                System.err.println("Aeron " + component + " warning");
                System.err.println(aeronException);
                return;
            }
            System.err.println("Aeron " + component + " failure");
            throwable.printStackTrace(System.err);
        };
    }

    static ThreadingMode coreThreadingMode() {
        String configured = System.getProperty("surprising.aeron.core.threading-mode");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv().get("AERON_CORE_THREADING_MODE");
        }
        if (configured == null || configured.isBlank()) {
            configured = Runtime.getRuntime().availableProcessors() >= 12 ? "DEDICATED" : "SHARED_NETWORK";
        }
        try {
            return ThreadingMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "surprising.aeron.core.threading-mode must be a valid Aeron ThreadingMode: " + configured,
                    exception);
        }
    }

    static long coreClientLivenessTimeoutNs() {
        return TimeUnit.SECONDS.toNanos(30);
    }

    static long corePublicationUnblockTimeoutNs() {
        return TimeUnit.SECONDS.toNanos(60);
    }

    static String clusterIngressChannel() {
        return configuredChannel("surprising.aeron.cluster-ingress-channel",
                "AERON_CLUSTER_INGRESS_CHANNEL", "aeron:udp?term-length=16m");
    }

    static String localArchiveControlChannel() {
        return configuredChannel("surprising.aeron.archive-local-control-channel",
                "AERON_ARCHIVE_LOCAL_CONTROL_CHANNEL", "aeron:ipc?term-length=1m");
    }

    private static String configuredChannel(String property, String environment, String defaultValue) {
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
