package com.surprising.realtime.provider.export;

import com.surprising.product.api.ProductLine;
import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ArchiveException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchiveReplayTest {
    @Test
    void oneThousandCommitIncrementsRequireOnlyTenBoundsRefreshes() {
        var aeron = mock(Aeron.class);
        var archive = mock(AeronArchive.class);
        when(aeron.addCounter(eq(0), anyString())).thenReturn(mock(Counter.class));
        when(archive.getStartPosition(11)).thenReturn(0L);
        when(archive.getStopPosition(11)).thenReturn(-1L);
        try (var replay = new CommittedTradeExporter.ArchiveReplay(aeron, archive, ProductLine.SPOT)) {
            for (int i = 0; i < 1000; i++)
                assertThat(replay.committedEnd(11, 1, i * 64L, (i + 1) * 64L, i * 1_000_000L))
                        .isEqualTo((i + 1) * 64L);
            verify(archive, times(10)).getStartPosition(11);
            verify(archive, times(10)).getStopPosition(11);
        }
    }

    @Test
    void cachesBoundsButImmediatelyRevalidatesTermChangesAndStoppedBoundaries() {
        var aeron = mock(Aeron.class);
        var archive = mock(AeronArchive.class);
        when(aeron.addCounter(eq(0), anyString())).thenReturn(mock(Counter.class));
        when(archive.getStartPosition(11)).thenReturn(0L);
        when(archive.getStopPosition(11)).thenReturn(-1L, 128L, 256L);
        try (var replay = new CommittedTradeExporter.ArchiveReplay(aeron, archive, ProductLine.SPOT)) {
            assertThat(replay.committedEnd(11, 1, 0, 64, 0)).isEqualTo(64);
            assertThat(replay.committedEnd(11, 1, 64, 256, 1)).isEqualTo(256);
            verify(archive, times(1)).getStartPosition(11);
            verify(archive, times(1)).getStopPosition(11);
            assertThat(replay.committedEnd(11, 2, 64, 256, 2)).isEqualTo(128);
            // The same recording can be extended; reaching a cached stop must refresh.
            assertThat(replay.committedEnd(11, 2, 128, 256, 3)).isEqualTo(256);
            verify(archive, times(3)).getStopPosition(11);
        }
    }

    @Test
    void periodicRevalidationRejectsTruncatedOrMissingHistory() {
        var aeron = mock(Aeron.class);
        var archive = mock(AeronArchive.class);
        when(aeron.addCounter(eq(0), anyString())).thenReturn(mock(Counter.class));
        when(archive.getStartPosition(11)).thenReturn(0L, 256L);
        when(archive.getStopPosition(11)).thenReturn(-1L);
        when(archive.getStartPosition(12)).thenReturn(-1L);
        try (var replay = new CommittedTradeExporter.ArchiveReplay(aeron, archive, ProductLine.SPOT)) {
            assertThat(replay.committedEnd(11, 1, 0, 64, 0)).isEqualTo(64);
            assertThatThrownBy(() -> replay.committedEnd(11, 1, 64, 128, 100_000_000L))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("archive gap");
            assertThatThrownBy(() -> replay.committedEnd(12, 3, 256, 512, 100_000_001L))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("archive gap");
        }
    }

    @Test
    void reusesReplayAcrossCommitIncrementsAndClosesItAtRecordingChange() {
        var aeron = mock(Aeron.class);
        var archive = mock(AeronArchive.class);
        var limit = mock(Counter.class);
        var first = mock(Subscription.class);
        var second = mock(Subscription.class);
        when(aeron.addCounter(eq(0), anyString())).thenReturn(limit);
        when(limit.id()).thenReturn(7);
        when(archive.startBoundedReplay(11, 0, AeronArchive.NULL_LENGTH, 7, "aeron:ipc", 22001)).thenReturn(101L);
        when(archive.startBoundedReplay(12, 256, AeronArchive.NULL_LENGTH, 7, "aeron:ipc", 22001)).thenReturn(102L);
        when(aeron.addSubscription("aeron:ipc?session-id=101", 22001)).thenReturn(first);
        when(aeron.addSubscription("aeron:ipc?session-id=102", 22001)).thenReturn(second);
        try (var replay = new CommittedTradeExporter.ArchiveReplay(aeron, archive, ProductLine.SPOT)) {
            replay.follow(11, 0, 128);
            replay.follow(11, 128, 192);
            replay.follow(11, 192, 256);
            verify(archive, times(1)).startBoundedReplay(anyLong(), anyLong(), anyLong(), anyInt(), anyString(), anyInt());
            verify(first, never()).close();
            verify(archive, never()).stopReplay(anyLong());
            replay.follow(12, 256, 384);
            verify(archive).stopReplay(101);
            verify(first).close();
        }
        var limits = inOrder(limit);
        limits.verify(limit).set(128);
        limits.verify(limit).set(192);
        limits.verify(limit).set(256);
        limits.verify(limit).set(384);
        limits.verify(limit).close();
        verify(archive).stopReplay(102);
        verify(second).close();
    }

    @Test
    void releasesCounterAndSubscriptionWhenStoppingReplayFails() {
        var aeron = mock(Aeron.class);
        var archive = mock(AeronArchive.class);
        var limit = mock(Counter.class);
        var subscription = mock(Subscription.class);
        when(aeron.addCounter(eq(0), anyString())).thenReturn(limit);
        when(aeron.addSubscription(anyString(), anyInt())).thenReturn(subscription);
        when(archive.startBoundedReplay(anyLong(), anyLong(), anyLong(), anyInt(), anyString(), anyInt())).thenReturn(101L);
        var failure = new ArchiveException("archive unavailable", ArchiveException.GENERIC);
        doThrow(failure).when(archive).stopReplay(101);
        var replay = new CommittedTradeExporter.ArchiveReplay(aeron, archive, ProductLine.SPOT);
        replay.follow(11, 0, 128);
        assertThatThrownBy(replay::close).isSameAs(failure);
        verify(subscription).close();
        verify(limit).close();
    }
}
