package com.surprising.aeron.service;

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import java.io.File;
import java.util.Locale;
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
                .termBufferSparseFile(true)
                .errorHandler(errorHandler("media-driver"));

        AeronArchive.Context replicationArchiveContext = new AeronArchive.Context()
                .controlResponseChannel("aeron:udp?endpoint=" + topology.hostname() + ":0");
        Archive.Context archiveContext = new Archive.Context()
                .aeronDirectoryName(aeronDirectoryName)
                .archiveDir(new File(nodeDirectory, "archive"))
                .controlChannel(topology.archiveControlChannel())
                .archiveClientContext(replicationArchiveContext)
                .localControlChannel("aeron:ipc?term-length=64k")
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
                .ingressChannel("aeron:udp?term-length=64k")
                .replicationChannel(topology.replicationChannel())
                .maxConcurrentSessions(maxConcurrentSessions)
                .archiveContext(localArchiveClient.clone())
                .errorHandler(errorHandler("consensus-module"));

        ClusteredServiceContainer.Context serviceContext = new ClusteredServiceContainer.Context()
                .clusterId(topology.clusterId())
                .aeronDirectoryName(aeronDirectoryName)
                .archiveContext(localArchiveClient.clone())
                .clusterDir(clusterDirectory)
                .clusteredService(new SurprisingClusteredService(topology.productLine()))
                .errorHandler(errorHandler("clustered-service"));

        try (ClusteredMediaDriver ignored = ClusteredMediaDriver.launch(
                    mediaDriverContext.terminationHook(barrier::signalAll),
                    archiveContext,
                    consensusContext.terminationHook(barrier::signalAll));
             ClusteredServiceContainer ignoredContainer = ClusteredServiceContainer.launch(
                     serviceContext.terminationHook(barrier::signalAll))) {
            System.out.printf("Aeron core started productLine=%s nodeId=%d clusterId=%d host=%s%n",
                    topology.productLine(), topology.nodeId(), topology.clusterId(), topology.hostname());
            barrier.await();
        }
    }

    private static ErrorHandler errorHandler(String component) {
        return throwable -> {
            System.err.println("Aeron " + component + " failure");
            throwable.printStackTrace(System.err);
        };
    }

    static ThreadingMode coreThreadingMode() {
        String configured = System.getProperty("surprising.aeron.core.threading-mode");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv().getOrDefault("AERON_CORE_THREADING_MODE", "SHARED");
        }
        try {
            return ThreadingMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "surprising.aeron.core.threading-mode must be a valid Aeron ThreadingMode: " + configured,
                    exception);
        }
    }
}
