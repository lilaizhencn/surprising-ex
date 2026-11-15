package com.surprising.realtime.provider.export;

import com.surprising.product.api.ProductLine;
import io.aeron.Aeron;
import io.aeron.Counter;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ArchiveException;
import java.util.concurrent.TimeUnit;

/** Owns one bounded replay and its limit counter for the lifetime of this export worker. */
final class CommittedTradeArchiveReplay implements AutoCloseable {
    private static final int STREAM_ID = 22001;
    private static final long BOUNDS_REFRESH_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private final Aeron aeron;
    private final AeronArchive archive;
    private final Counter limit;
    private long recordingId = Aeron.NULL_VALUE;
    private long sessionId = Aeron.NULL_VALUE;
    private Subscription subscription;
    // Export-worker-owned Archive metadata, revalidated on a new term/recording,
    // every 100ms, and whenever the cached stopped boundary is reached.
    private long boundsRecordingId = Aeron.NULL_VALUE, boundsTermId = Aeron.NULL_VALUE;
    private long recordingStart, recordingStop, boundsCheckedAt;

    CommittedTradeArchiveReplay(Aeron aeron, AeronArchive archive, ProductLine product) {
        this.aeron = aeron;
        this.archive = archive;
        this.limit = aeron.addCounter(0, "trade-export replay limit " + product);
    }

    long committedEnd(long recordingId, long termId, long position, long committed, long now) {
        if (boundsRecordingId != recordingId || boundsTermId != termId
                || now - boundsCheckedAt >= BOUNDS_REFRESH_NANOS
                || (recordingStop >= 0 && position >= recordingStop)) {
            long start = archive.getStartPosition(recordingId);
            long stop = archive.getStopPosition(recordingId);
            recordingStart = start;
            recordingStop = stop;
            boundsRecordingId = recordingId;
            boundsTermId = termId;
            boundsCheckedAt = now;
        }
        long end = recordingStop >= 0 ? Math.min(committed, recordingStop) : committed;
        if (recordingStart < 0 || recordingStart > position || end <= position)
            throw new IllegalStateException("archive gap at trade export checkpoint " + position);
        return end;
    }

    long recordingId() {
        return recordingId;
    }

    Subscription subscription() {
        return subscription;
    }

    long position(long initialPosition) {
        return subscription == null || subscription.imageCount() == 0
                ? initialPosition : subscription.imageAtIndex(0).position();
    }

    void follow(long recordingId, long position, long committed) {
        if (this.recordingId == recordingId) {
            limit.set(committed);
            return;
        }
        closeReplay();
        limit.set(committed);
        sessionId = archive.startBoundedReplay(recordingId, position, AeronArchive.NULL_LENGTH,
                limit.id(), "aeron:ipc", STREAM_ID);
        subscription = aeron.addSubscription("aeron:ipc?session-id=" + (int) sessionId, STREAM_ID);
        this.recordingId = recordingId;
    }

    private void closeReplay() {
        try {
            if (sessionId != Aeron.NULL_VALUE) {
                try {
                    archive.stopReplay(sessionId);
                } catch (ArchiveException failure) {
                    // A stopped recording can finish its replay before we switch recordings.
                    if (failure.errorCode() != ArchiveException.UNKNOWN_REPLAY)
                        throw failure;
                }
            }
        } finally {
            sessionId = Aeron.NULL_VALUE;
            recordingId = Aeron.NULL_VALUE;
            if (subscription != null) {
                subscription.close();
                subscription = null;
            }
        }
    }

    @Override
    public void close() {
        try {
            closeReplay();
        } finally {
            limit.close();
        }
    }
}
