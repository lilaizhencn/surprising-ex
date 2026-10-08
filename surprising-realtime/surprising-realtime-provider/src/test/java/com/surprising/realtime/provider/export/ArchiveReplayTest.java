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
