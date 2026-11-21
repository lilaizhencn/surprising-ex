package com.surprising.aeron.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveHistoryRetentionTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final Duration WEEK = Duration.ofDays(7);

    @Test
    void keepsOneWeekAndDoesNotPassTheExportCursorOrLatestSnapshot() {
        long cutoff = NOW - WEEK.toMillis();
        var snapshots = history(
                100, cutoff - 86_400_000L,
                500, cutoff,
                900, NOW - 60_000L);

        assertThat(ArchiveHistoryRetention.commandLogPurgePosition(snapshots, 800L, NOW, WEEK)).isEqualTo(500L);
        assertThat(ArchiveHistoryRetention.commandLogPurgePosition(snapshots, 450L, NOW, WEEK)).isEqualTo(450L);
        assertThat(ArchiveHistoryRetention.commandLogPurgePosition(snapshots, 2_000L, NOW, WEEK)).isEqualTo(500L);
    }

    @Test
    void refusesToPurgeWithoutAnExportCursorOrACompleteSnapshot() {
        var complete = history(500, NOW - Duration.ofDays(8).toMillis());

        assertThat(ArchiveHistoryRetention.commandLogPurgePosition(complete, null, NOW, WEEK)).isEqualTo(-1L);
        assertThat(ArchiveHistoryRetention.commandLogPurgePosition(complete, 0L, NOW, WEEK)).isEqualTo(-1L);
        assertThat(ArchiveHistoryRetention.commandLogPurgePosition(List.of(
                new ArchiveHistoryRetention.Snapshot(500, NOW - Duration.ofDays(8).toMillis(), -1, true)),
                500L, NOW, WEEK)).isEqualTo(-1L);
    }

    @Test
    void waitsUntilASnapshotIsOlderThanTheRetentionWindow() {
        var recent = history(700, NOW - Duration.ofHours(1).toMillis());

        assertThat(ArchiveHistoryRetention.commandLogPurgePosition(recent, 700L, NOW, WEEK)).isEqualTo(-1L);
    }

    @Test
    void readsTheLiveExportCursorWithoutLoadingItsSnapshot(@TempDir Path dir) throws Exception {
        Path checkpoint = dir.resolve("checkpoint.bin");
        byte[] name = "LINEAR_PERPETUAL".getBytes(StandardCharsets.UTF_8);
        ByteBuffer header = ByteBuffer.allocate(4 + 2 + name.length + 8 + 8 + 4);
        header.putInt(0x54584331);
        header.putShort((short) name.length);
        header.put(name);
        header.putLong(640L);
        header.putLong(12L);
        header.putInt(8);
        Files.write(checkpoint, header.array());
        Files.write(checkpoint, new byte[8 + 32], java.nio.file.StandardOpenOption.APPEND);

        assertThat(ArchiveHistoryRetention.readExportLogPosition(checkpoint)).isEqualTo(640L);
    }

    @Test
    void rejectsARepairCheckpoint(@TempDir Path dir) throws Exception {
        Path checkpoint = dir.resolve("checkpoint.bin.command-repair");
        Files.write(checkpoint, new byte[] {0, 0, 0, 2});

        assertThatThrownBy(() -> ArchiveHistoryRetention.readExportLogPosition(checkpoint))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("live exporter");
    }

    private static List<ArchiveHistoryRetention.Snapshot> history(long... positionAndTimestamp) {
        var snapshots = new java.util.ArrayList<ArchiveHistoryRetention.Snapshot>();
        for (int i = 0; i < positionAndTimestamp.length; i += 2) {
            long position = positionAndTimestamp[i];
            long timestamp = positionAndTimestamp[i + 1];
            snapshots.add(new ArchiveHistoryRetention.Snapshot(position, timestamp, -1, true));
            snapshots.add(new ArchiveHistoryRetention.Snapshot(position, timestamp, 0, true));
        }
        return snapshots;
    }
}
