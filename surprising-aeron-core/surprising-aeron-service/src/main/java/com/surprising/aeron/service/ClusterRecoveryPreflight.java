package com.surprising.aeron.service;

import com.surprising.aeron.protocol.ProductLineClusterLayout;
import io.aeron.Aeron;
import io.aeron.cluster.RecordingLog;
import io.aeron.cluster.service.ClusterMarkFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.agrona.BitUtil;
import org.agrona.concurrent.SystemEpochClock;

final class ClusterRecoveryPreflight {

    private static final int QUORUM = ProductLineClusterLayout.MEMBER_COUNT / 2 + 1;
    private static final long ACTIVE_WINDOW_MILLIS = 10_000;

    private ClusterRecoveryPreflight() {
    }

    static void verify(Path productDirectory) {
        List<Path> recordingLogs = recordingLogs(productDirectory);
        long existingRecordingLogs = recordingLogs.stream().filter(Files::exists).count();
        if (existingRecordingLogs == 0) return;
        if (hasActiveMember(productDirectory)) return;
        if (existingRecordingLogs < ProductLineClusterLayout.MEMBER_COUNT) {
            boolean completeLocalTree = recordingLogs.stream()
                    .map(Path::getParent)
                    .allMatch(Files::isDirectory);
            if (!completeLocalTree) return;
            throw new IllegalStateException("Aeron cold-start metadata is incomplete: recordingLogs="
                    + existingRecordingLogs + " requiredMembers=" + ProductLineClusterLayout.MEMBER_COUNT
                    + "; refusing recovery before Aeron launch");
        }

        List<RecordingLog.Entry> lastTerms = new ArrayList<>(ProductLineClusterLayout.MEMBER_COUNT);
        for (int memberId = 0; memberId < ProductLineClusterLayout.MEMBER_COUNT; memberId++) {
            Path clusterDirectory = clusterDirectory(productDirectory, memberId);
            lastTerms.add(readLastTerm(clusterDirectory, memberId));
        }

        RecordingLog.Entry reference = lastTerms.getFirst();
        for (int memberId = 1; memberId < lastTerms.size(); memberId++) {
            RecordingLog.Entry candidate = lastTerms.get(memberId);
            if (candidate.leadershipTermId != reference.leadershipTermId
                    || candidate.termBaseLogPosition != reference.termBaseLogPosition) {
                throw new IllegalStateException("Aeron cold-start metadata disagrees on latest term: memberId="
                        + memberId + " expectedTerm=" + reference.leadershipTermId
                        + " actualTerm=" + candidate.leadershipTermId
                        + " expectedTermBaseLogPosition=" + reference.termBaseLogPosition
                        + " actualTermBaseLogPosition=" + candidate.termBaseLogPosition
                        + "; refusing recovery before Aeron launch");
            }
        }

        List<Long> positions = lastTerms.stream().map(entry -> entry.logPosition).toList();
        Map<Long, Integer> committedCounts = new LinkedHashMap<>();
        positions.stream()
                .filter(position -> position != Aeron.NULL_VALUE)
                .forEach(position -> committedCounts.merge(position, 1, Math::addExact));
        if (committedCounts.values().stream().noneMatch(count -> count >= QUORUM)) {
            throw new IllegalStateException("Aeron cold-start metadata has no committed quorum: term="
                    + reference.leadershipTermId + " termBaseLogPosition=" + reference.termBaseLogPosition
                    + " committedPositions=" + positions + " requiredMembers=" + QUORUM
                    + "; refusing recovery before Aeron launch");
        }
    }

    private static List<Path> recordingLogs(Path productDirectory) {
        List<Path> paths = new ArrayList<>(ProductLineClusterLayout.MEMBER_COUNT);
        for (int memberId = 0; memberId < ProductLineClusterLayout.MEMBER_COUNT; memberId++) {
            paths.add(clusterDirectory(productDirectory, memberId).resolve(RecordingLog.RECORDING_LOG_FILE_NAME));
        }
        return List.copyOf(paths);
    }

    private static boolean hasActiveMember(Path productDirectory) {
        long now = System.currentTimeMillis();
        for (int memberId = 0; memberId < ProductLineClusterLayout.MEMBER_COUNT; memberId++) {
            Path clusterDirectory = clusterDirectory(productDirectory, memberId);
            Path markPath = clusterDirectory.resolve(ClusterMarkFile.FILENAME);
            if (!Files.exists(markPath)) continue;
            try (ClusterMarkFile markFile = new ClusterMarkFile(clusterDirectory.toFile(),
                    ClusterMarkFile.FILENAME, SystemEpochClock.INSTANCE, 0, ignored -> { })) {
                long timestamp = markFile.activityTimestampVolatile();
                long pid = markFile.decoder().pid();
                if (timestamp > 0 && timestamp >= now - ACTIVE_WINDOW_MILLIS && pid > 0
                        && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Path clusterDirectory(Path productDirectory, int memberId) {
        return productDirectory.resolve("node" + memberId).resolve("cluster");
    }

    private static RecordingLog.Entry readLastTerm(Path clusterDirectory, int memberId) {
        Path recordingLogPath = clusterDirectory.resolve(RecordingLog.RECORDING_LOG_FILE_NAME);
        try {
            long size = Files.size(recordingLogPath);
            if (size == 0 || size % BitUtil.CACHE_LINE_LENGTH != 0) {
                throw unreadableRecordingLog(clusterDirectory, memberId,
                        new IllegalStateException("invalid recording log size=" + size));
            }
        } catch (IOException failure) {
            throw unreadableRecordingLog(clusterDirectory, memberId, failure);
        }
        RecordingLog recordingLog;
        try {
            recordingLog = new RecordingLog(clusterDirectory.toFile(), false);
        } catch (RuntimeException failure) {
            throw unreadableRecordingLog(clusterDirectory, memberId, failure);
        }
        try (recordingLog) {
            RecordingLog.Entry lastTerm = recordingLog.findLastTerm();
            if (lastTerm == null) {
                throw new IllegalStateException("Aeron cold-start metadata has no leadership term: memberId="
                        + memberId + "; refusing recovery before Aeron launch");
            }
            return lastTerm;
        }
    }

    private static IllegalStateException unreadableRecordingLog(
            Path clusterDirectory, int memberId, Exception failure) {
        return new IllegalStateException("Aeron cold-start recording log is unreadable: memberId="
                + memberId + " path=" + clusterDirectory.resolve(RecordingLog.RECORDING_LOG_FILE_NAME)
                + "; refusing recovery before Aeron launch", failure);
    }
}
