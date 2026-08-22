package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.aeron.Aeron;
import io.aeron.cluster.RecordingLog;
import io.aeron.cluster.codecs.mark.ClusterComponentType;
import io.aeron.cluster.service.ClusterMarkFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.agrona.concurrent.SystemEpochClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClusterRecoveryPreflightTest {

    private static final long COMMIT_POSITION = 129_710_944L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void validStoppedClusterMetadataHasCommittedPositionOnEveryMember() throws Exception {
        Path productDirectory = temporaryDirectory.resolve("linear_perpetual");
        for (int memberId = 0; memberId < 3; memberId++) {
            writeRecordingLog(productDirectory, memberId, COMMIT_POSITION);
        }

        for (int memberId = 0; memberId < 3; memberId++) {
            Path clusterDirectory = productDirectory.resolve("node" + memberId).resolve("cluster");
            try (RecordingLog recordingLog = new RecordingLog(clusterDirectory.toFile(), false)) {
                RecordingLog.Entry lastTerm = recordingLog.findLastTerm();
                assertThat(lastTerm.leadershipTermId).isEqualTo(3);
                assertThat(lastTerm.termBaseLogPosition).isEqualTo(107_097_344L);
                assertThat(lastTerm.logPosition).isEqualTo(COMMIT_POSITION);
            }
        }
    }

    @Test
    void interruptedClusterWithoutCommittedQuorumFailsBeforeAeronLaunch() throws Exception {
        Path productDirectory = temporaryDirectory.resolve("linear_perpetual");
        writeRecordingLog(productDirectory, 0, COMMIT_POSITION);
        writeRecordingLog(productDirectory, 1, Aeron.NULL_VALUE);
        writeRecordingLog(productDirectory, 2, Aeron.NULL_VALUE);

        assertThatIllegalStateException()
                .isThrownBy(() -> ClusterRecoveryPreflight.verify(productDirectory))
                .withMessage("Aeron cold-start metadata has no committed quorum: "
                        + "term=3 termBaseLogPosition=107097344 committedPositions=[129710944, -1, -1] "
                        + "requiredMembers=2; refusing recovery before Aeron launch");
    }

    @Test
    void twoMatchingCommittedMembersEstablishStoppedQuorum() throws Exception {
        Path productDirectory = temporaryDirectory.resolve("linear_perpetual");
        writeRecordingLog(productDirectory, 0, COMMIT_POSITION);
        writeRecordingLog(productDirectory, 1, COMMIT_POSITION);
        writeRecordingLog(productDirectory, 2, Aeron.NULL_VALUE);

        ClusterRecoveryPreflight.verify(productDirectory);
    }

    @Test
    void freshClusterWithoutRecordingLogsPassesPreflight() {
        ClusterRecoveryPreflight.verify(temporaryDirectory.resolve("linear_perpetual"));
    }

    @Test
    void incompleteStoppedMetadataFailsBeforeAeronLaunch() throws Exception {
        Path productDirectory = temporaryDirectory.resolve("linear_perpetual");
        writeRecordingLog(productDirectory, 0, COMMIT_POSITION);
        Files.createDirectories(productDirectory.resolve("node1").resolve("cluster"));
        Files.createDirectories(productDirectory.resolve("node2").resolve("cluster"));

        assertThatIllegalStateException()
                .isThrownBy(() -> ClusterRecoveryPreflight.verify(productDirectory))
                .withMessage("Aeron cold-start metadata is incomplete: recordingLogs=1 requiredMembers=3; "
                        + "refusing recovery before Aeron launch");
    }

    @Test
    void distributedMemberWithOnlyItsLocalNodeDirectoryPassesPreflight() throws Exception {
        Path productDirectory = temporaryDirectory.resolve("linear_perpetual");
        writeRecordingLog(productDirectory, 0, COMMIT_POSITION);

        ClusterRecoveryPreflight.verify(productDirectory);
    }

    @Test
    void activeMemberAllowsAnotherMemberToRejoinWithPartialLocalMetadata() throws Exception {
        Path productDirectory = temporaryDirectory.resolve("linear_perpetual");
        writeRecordingLog(productDirectory, 0, COMMIT_POSITION);
        Path memberZero = productDirectory.resolve("node0").resolve("cluster");
        try (ClusterMarkFile markFile = new ClusterMarkFile(
                memberZero.resolve(ClusterMarkFile.FILENAME).toFile(),
                ClusterComponentType.CONSENSUS_MODULE, ClusterMarkFile.ERROR_BUFFER_MIN_LENGTH,
                SystemEpochClock.INSTANCE, 5_000, 4_096)) {
            markFile.encoder().pid(ProcessHandle.current().pid());
            markFile.signalReady(0);
            markFile.updateActivityTimestamp(System.currentTimeMillis());

            ClusterRecoveryPreflight.verify(productDirectory);
        }
    }

    @Test
    void stoppedMembersWithDifferentLatestTermsFailBeforeAeronLaunch() throws Exception {
        Path productDirectory = temporaryDirectory.resolve("linear_perpetual");
        writeRecordingLog(productDirectory, 0, COMMIT_POSITION);
        writeRecordingLog(productDirectory, 1, COMMIT_POSITION);
        writeRecordingLog(productDirectory, 2, COMMIT_POSITION);
        Path memberTwo = productDirectory.resolve("node2").resolve("cluster");
        try (RecordingLog recordingLog = new RecordingLog(memberTwo.toFile(), false)) {
            recordingLog.appendTerm(0, 4, COMMIT_POSITION, 5);
            recordingLog.commitLogPosition(4, COMMIT_POSITION + 64);
        }

        assertThatIllegalStateException()
                .isThrownBy(() -> ClusterRecoveryPreflight.verify(productDirectory))
                .withMessageContaining("Aeron cold-start metadata disagrees on latest term: memberId=2")
                .withMessageEndingWith("; refusing recovery before Aeron launch");
    }

    @Test
    void corruptRecordingLogFailsClosedWithMemberAndPath() throws Exception {
        Path productDirectory = temporaryDirectory.resolve("linear_perpetual");
        writeRecordingLog(productDirectory, 0, COMMIT_POSITION);
        writeRecordingLog(productDirectory, 1, COMMIT_POSITION);
        Path corruptClusterDirectory = productDirectory.resolve("node2").resolve("cluster");
        Files.createDirectories(corruptClusterDirectory);
        Files.write(corruptClusterDirectory.resolve(RecordingLog.RECORDING_LOG_FILE_NAME),
                new byte[]{1, 2, 3});

        assertThatIllegalStateException()
                .isThrownBy(() -> ClusterRecoveryPreflight.verify(productDirectory))
                .withMessageStartingWith("Aeron cold-start recording log is unreadable: memberId=2 path=")
                .withMessageEndingWith("; refusing recovery before Aeron launch");
    }

    private static void writeRecordingLog(Path productDirectory, int memberId, long commitPosition)
            throws Exception {
        Path clusterDirectory = productDirectory.resolve("node" + memberId).resolve("cluster");
        Files.createDirectories(clusterDirectory);
        try (RecordingLog recordingLog = new RecordingLog(clusterDirectory.toFile(), true)) {
            recordingLog.appendTerm(0, 0, 0, 1);
            recordingLog.appendTerm(0, 1, 9_859_648L, 2);
            recordingLog.appendTerm(0, 2, 9_859_648L, 3);
            recordingLog.appendTerm(0, 3, 107_097_344L, 4);
            if (commitPosition != Aeron.NULL_VALUE) {
                recordingLog.commitLogPosition(3, commitPosition);
            }
        }
    }
}
