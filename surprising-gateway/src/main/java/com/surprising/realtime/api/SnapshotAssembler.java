package com.surprising.realtime.api;

import com.surprising.aeron.protocol.RealtimeFrame;

import java.util.*;

/** Bounded incomplete snapshots expire; only a contiguous, fenced batch can be installed. */
public final class SnapshotAssembler {
    private static final int MAX_PARTS = 8192, MAX_BYTES = 8 * 1024 * 1024, MAX_PENDING = 32;
    private final Map<String, Pending> pending = new HashMap<>();

    public List<RealtimeFrame> accept(RealtimeFrame f, long now) {
        pending.values().removeIf(p -> now - p.started > 5000);
        String key =
                f.snapshotId() == 0
                        ? f.productLine() + ":commit:" + f.sequence()
                        : f.productLine() + ":" + f.userId() + ":" + f.snapshotId();
        if ((f.kind() == RealtimeFrame.Kind.SNAPSHOT_BEGIN
                || f.kind() == RealtimeFrame.Kind.COMMIT_BEGIN)) {
            if (f.ordinal() != 0
                    || (f.snapshotId() <= 0 && f.kind() != RealtimeFrame.Kind.COMMIT_BEGIN)
                    || pending.size() >= MAX_PENDING) return List.of();
            pending.put(key, new Pending(now));
        }
        Pending p = pending.get(key);
        if (p == null) return List.of();
        if (f.ordinal() != p.frames.size()
                || p.frames.size() >= MAX_PARTS
                || (p.bytes += f.payloadLength() + 448) > MAX_BYTES
                || (!p.frames.isEmpty() && f.sequence() != p.frames.getFirst().sequence())) {
            pending.remove(key);
            return List.of();
        }
        p.frames.add(f);
        if (f.kind() != RealtimeFrame.Kind.SNAPSHOT_END
                && f.kind() != RealtimeFrame.Kind.COMMIT_END) return List.of();
        pending.remove(key);
        return List.copyOf(p.frames);
    }

    private static final class Pending {
        final long started;
        int bytes;
        final List<RealtimeFrame> frames = new ArrayList<>();

        Pending(long now) {
            started = now;
        }
    }
}
