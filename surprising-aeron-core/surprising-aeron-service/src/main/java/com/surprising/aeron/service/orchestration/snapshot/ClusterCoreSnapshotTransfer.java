package com.surprising.aeron.service.orchestration.snapshot;

import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.cluster.service.Cluster;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import org.agrona.concurrent.UnsafeBuffer;

/** Aeron Cluster 快照的分段接收与发布边界。 */
public final class ClusterCoreSnapshotTransfer {
    private static final long DEADLINE_NS = TimeUnit.SECONDS.toNanos(
            Long.getLong("surprising.aeron.snapshot-timeout-seconds", 300L));

    /** 从 Cluster 恢复 Image 接收快照片段；首次启动没有快照时返回 {@code null}。 */
    public SectionedCoreSnapshotCodec.RecoveryBuffer read(Image image, Cluster cluster) {
        if (image == null) return null;
        var recovery = new SectionedCoreSnapshotCodec.RecoveryBuffer();
        long deadline = System.nanoTime() + DEADLINE_NS;
        while (!image.isEndOfStream()) {
            int work = image.poll((buffer, offset, length, header) ->
                    recovery.accept(buffer, offset, length), 10);
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("snapshot recovery deadline");
            }
            cluster.idleStrategy().idle(work);
        }
        return recovery;
    }

    /** 将 Owner 捕获的分段快照发布到 Cluster；背压等待交由 Cluster 服务推进。 */
    public void write(SectionedCoreSnapshotCodec.SectionedSnapshot snapshot,
                      ExclusivePublication publication, LongConsumer awaitProgress) {
        long deadline = System.nanoTime() + DEADLINE_NS;
        for (byte[] chunk : snapshot.chunks()) {
            var buffer = new UnsafeBuffer(chunk);
            for (int offset = 0; offset < chunk.length;) {
                int length = Math.min(publication.maxPayloadLength(), chunk.length - offset);
                long result = publication.offer(buffer, offset, length);
                if (result >= 0) {
                    offset += length;
                } else {
                    if (result != Publication.BACK_PRESSURED
                            && result != Publication.ADMIN_ACTION
                            && result != Publication.NOT_CONNECTED) {
                        throw new IllegalStateException("snapshot publication failed: " + result);
                    }
                    if (System.nanoTime() > deadline) {
                        throw new IllegalStateException("snapshot publication deadline");
                    }
                    awaitProgress.accept(deadline);
                }
            }
        }
    }
}
