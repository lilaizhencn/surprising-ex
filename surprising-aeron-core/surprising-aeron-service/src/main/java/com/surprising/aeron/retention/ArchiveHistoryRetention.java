package com.surprising.aeron.retention;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Decides how far a live cluster command log can be purged while recovery and trade export remain possible. */
public final class ArchiveHistoryRetention {
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);
    public static final int CONSENSUS_SERVICE_ID = -1;
    public static final int TRADING_SERVICE_ID = 0;
    private static final int EXPORT_CHECKPOINT_MAGIC = 0x54584331;
    private static final int EXPORT_CHECKPOINT_HASH_BYTES = 32;

    private ArchiveHistoryRetention() {}

    public record Snapshot(long logPosition, long timestampMillis, int serviceId, boolean valid) {}

    /**
     * @param exportedLogPosition durable trade-export cursor, or {@code null} when that cursor is unknown
     * @return the command-log position that may be purged up to, or {@code -1} when nothing may be removed
     */
    public static long commandLogPurgePosition(List<Snapshot> snapshots, Long exportedLogPosition,
                                               long nowMillis, Duration retention) {
        if (exportedLogPosition == null || exportedLogPosition <= 0 || retention == null || retention.isZero()
                || retention.isNegative() || nowMillis < 0) {
            return -1;
        }
        long cutoff = nowMillis - retention.toMillis();
        long newestRecoverable = newestCompleteSnapshot(snapshots);
        if (newestRecoverable < 0) {
            return -1;
        }
        long oldestHistory = newestPositionAtOrBefore(snapshots, cutoff);
        if (oldestHistory < 0) {
            return -1;
        }
        long purgePosition = Math.min(oldestHistory, exportedLogPosition);
        if (purgePosition > newestRecoverable) {
            purgePosition = newestRecoverable;
        }
        return purgePosition > 0 ? purgePosition : -1;
    }

    /** Reads the live trade-export cursor. Repair checkpoints use a different file and are ignored by the caller. */
    public static long readExportLogPosition(Path checkpoint) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
        try (FileChannel channel = FileChannel.open(checkpoint, StandardOpenOption.READ)) {
            while (header.hasRemaining() && channel.read(header) > 0) {
                // A short product name always fits in the first page.
            }
            header.flip();
            if (header.remaining() < 4) {
                throw new IOException("trade export checkpoint is truncated");
            }
            if (header.getInt() != EXPORT_CHECKPOINT_MAGIC) {
                throw new IOException("trade export checkpoint purpose is not the live exporter");
            }
            if (header.remaining() < 2 + 8 + 8 + 4) {
                throw new IOException("trade export checkpoint is truncated");
            }
            int nameLength = Short.toUnsignedInt(header.getShort());
            if (nameLength <= 0 || nameLength > header.remaining()) {
                throw new IOException("trade export checkpoint product name is invalid");
            }
            byte[] name = new byte[nameLength];
            header.get(name);
            if (new String(name, StandardCharsets.UTF_8).isBlank()) {
                throw new IOException("trade export checkpoint product name is blank");
            }
            if (header.remaining() < 8 + 8 + 4) {
                throw new IOException("trade export checkpoint is truncated");
            }
            long position = header.getLong();
            long sequence = header.getLong();
            int payloadLength = header.getInt();
            long headerBytes = 4L + 2 + nameLength + 8 + 8 + 4;
            if (position < 0 || sequence < 0 || payloadLength <= 0
                    || channel.size() < headerBytes + payloadLength + EXPORT_CHECKPOINT_HASH_BYTES) {
                throw new IOException("trade export checkpoint identity or length is invalid");
            }
            return position;
        }
    }

    private static long newestCompleteSnapshot(List<Snapshot> snapshots) {
        return completeSnapshots(snapshots).stream()
                .max(Comparator.comparingLong(Snapshot::logPosition))
                .map(Snapshot::logPosition)
                .orElse(-1L);
    }

    private static long newestPositionAtOrBefore(List<Snapshot> snapshots, long cutoffMillis) {
        return completeSnapshots(snapshots).stream()
                .filter(snapshot -> snapshot.timestampMillis() <= cutoffMillis)
                .max(Comparator.comparingLong(Snapshot::logPosition))
                .map(Snapshot::logPosition)
                .orElse(-1L);
    }

    private static List<Snapshot> completeSnapshots(List<Snapshot> snapshots) {
        Map<Long, List<Snapshot>> byPosition = new HashMap<>();
        for (Snapshot snapshot : snapshots) {
            if (snapshot != null && snapshot.valid()
                    && (snapshot.serviceId() == CONSENSUS_SERVICE_ID || snapshot.serviceId() == TRADING_SERVICE_ID)) {
                byPosition.computeIfAbsent(snapshot.logPosition(), ignored -> new ArrayList<>()).add(snapshot);
            }
        }
        List<Snapshot> complete = new ArrayList<>();
        for (List<Snapshot> group : byPosition.values()) {
            boolean consensus = false;
            boolean trading = false;
            Snapshot newest = null;
            for (Snapshot snapshot : group) {
                consensus |= snapshot.serviceId() == CONSENSUS_SERVICE_ID;
                trading |= snapshot.serviceId() == TRADING_SERVICE_ID;
                if (newest == null || snapshot.timestampMillis() > newest.timestampMillis()) {
                    newest = snapshot;
                }
            }
            if (consensus && trading && newest != null) {
                complete.add(newest);
            }
        }
        return complete;
    }
}
