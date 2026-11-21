package com.surprising.aeron.retention;

import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ArchiveException;
import io.aeron.cluster.RecordingLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Purges command-log segments older than the retained history on one running node. */
public final class ArchiveHistoryRetentionMain {
    private ArchiveHistoryRetentionMain() {}

    public static void main(String[] args) throws Exception {
        Path clusterDir = null;
        Path aeronDir = null;
        Path checkpoint = null;
        String controlChannel = "aeron:ipc?term-length=1m";
        int retentionDays = 7;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--cluster-dir" -> clusterDir = Path.of(required(args, ++i, arg));
                case "--aeron-dir" -> aeronDir = Path.of(required(args, ++i, arg));
                case "--checkpoint" -> checkpoint = Path.of(required(args, ++i, arg));
                case "--control-channel" -> controlChannel = required(args, ++i, arg);
                case "--retention-days" -> retentionDays = Integer.parseInt(required(args, ++i, arg));
                default -> throw new IllegalArgumentException("unknown argument " + arg);
            }
        }
        if (clusterDir == null || aeronDir == null || checkpoint == null) {
            throw new IllegalArgumentException("cluster dir, aeron dir and export checkpoint are required");
        }
        if (retentionDays < 1) {
            throw new IllegalArgumentException("retention days must be positive");
        }
        if (!Files.isRegularFile(checkpoint)) {
            System.out.println("RETENTION=SKIPPED reason=export-checkpoint-missing");
            return;
        }
        long exported = ArchiveHistoryRetention.readExportLogPosition(checkpoint);
        List<ArchiveHistoryRetention.Snapshot> snapshots = new ArrayList<>();
        Set<Long> termRecordings = new LinkedHashSet<>();
        try (RecordingLog recordingLog = new RecordingLog(clusterDir.toFile(), false)) {
            for (RecordingLog.Entry entry : recordingLog.entries()) {
                if (entry.type == RecordingLog.ENTRY_TYPE_TERM && entry.isValid) {
                    termRecordings.add(entry.recordingId);
                } else if (entry.type == RecordingLog.ENTRY_TYPE_SNAPSHOT) {
                    snapshots.add(new ArchiveHistoryRetention.Snapshot(
                            entry.logPosition, entry.timestamp, entry.serviceId, entry.isValid));
                }
            }
        }
        long purgePosition = ArchiveHistoryRetention.commandLogPurgePosition(
                snapshots, exported, System.currentTimeMillis(), Duration.ofDays(retentionDays));
        if (purgePosition < 0 || termRecordings.isEmpty()) {
            System.out.printf("RETENTION=IDLE exported=%d purgePosition=%d%n", exported, purgePosition);
            return;
        }
        long deleted = 0;
        try (AeronArchive archive = AeronArchive.connect(new AeronArchive.Context()
                .aeronDirectoryName(aeronDir.toString())
                .controlRequestChannel(controlChannel)
                .controlResponseChannel(controlChannel))) {
            for (long recordingId : termRecordings) {
                deleted += purge(archive, recordingId, purgePosition);
            }
        }
        System.out.printf("RETENTION=PURGED position=%d segments=%d%n", purgePosition, deleted);
    }

    private static long purge(AeronArchive archive, long recordingId, long purgePosition) {
        long[] start = {-1};
        int[] termLength = {0};
        int[] segmentLength = {0};
        if (archive.listRecording(recordingId, (controlSessionId, correlationId, id, startTimestamp, stopTimestamp,
                startPosition, stopPosition, initialTermId, segmentFileLength, termBufferLength, mtuLength,
                sessionId, streamId, strippedChannel, originalChannel, sourceIdentity) -> {
            start[0] = startPosition;
            termLength[0] = termBufferLength;
            segmentLength[0] = segmentFileLength;
        }) < 1 || start[0] < 0) {
            return 0;
        }
        long aligned = AeronArchive.segmentFileBasePosition(
                start[0], purgePosition, termLength[0], segmentLength[0]);
        if (aligned <= start[0]) {
            return 0;
        }
        try {
            return archive.purgeSegments(recordingId, aligned);
        } catch (ArchiveException busy) {
            System.out.printf("RETENTION=RECORDING_BUSY recordingId=%d reason=%s%n", recordingId, busy.getMessage());
            return 0;
        }
    }

    private static String required(String[] args, int index, String name) {
        if (index >= args.length || args[index].isBlank()) {
            throw new IllegalArgumentException(name + " requires a value");
        }
        return args[index];
    }
}
