package com.surprising.aeron.benchmarks.transport;

import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import java.nio.ByteOrder;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import com.surprising.aeron.protocol.CoreProtocol;

/**
 * 复制诊断服务：真实订单字节进入已提交日志后校验顺序并确认，不执行任何交易业务。
 * 只供新建的一次性诊断集群使用；不能恢复生产日志或替换生产服务。
 */
public final class CommandReplicationService implements ClusteredService {
    /** 服务线程独占的诊断序号、字节计数与可复用确认缓冲区。 */
    private long sequence, bytes;
    private Cluster cluster;
    private final UnsafeBuffer ack = new UnsafeBuffer(new byte[Long.BYTES]);

    public void onStart(Cluster cluster, Image snapshot) {
        if (snapshot != null) throw new IllegalStateException("fresh diagnostic cluster required");
        this.cluster = cluster;
    }

    public void onSessionMessage(ClientSession session, long timestamp,
            DirectBuffer buffer, int offset, int length, Header header) {
        if (length < CoreProtocol.HEADER_LENGTH
                || buffer.getInt(offset, ByteOrder.LITTLE_ENDIAN) != CoreProtocol.MAGIC
                || buffer.getInt(offset + 72, ByteOrder.LITTLE_ENDIAN) != length - CoreProtocol.HEADER_LENGTH)
            throw new IllegalStateException("invalid diagnostic command");
        long next = buffer.getLong(offset + 40, ByteOrder.LITTLE_ENDIAN);
        if (next != sequence + 1) throw new IllegalStateException("command sequence gap");
        sequence = next;
        bytes += length;
        if (cluster.role() == Cluster.Role.LEADER && session != null) {
            ack.putLong(0, sequence, ByteOrder.LITTLE_ENDIAN);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (session.offer(ack, 0, Long.BYTES) < 0) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("ack timeout");
                cluster.idleStrategy().idle();
            }
        }
    }

    public void onSessionOpen(ClientSession session, long timestamp) {}
    public void onSessionClose(ClientSession session, long timestamp, CloseReason reason) {
        System.out.printf("replicationSessionClosed sequence=%d bytes=%d%n", sequence, bytes);
    }
    public void onTimerEvent(long correlationId, long timestamp) {}
    public void onTakeSnapshot(ExclusivePublication publication) {
        throw new UnsupportedOperationException("snapshot is outside replication diagnostic");
    }
    public void onRoleChange(Cluster.Role role) {}
    public void onTerminate(Cluster cluster) {
        System.out.printf("replicationApplied sequence=%d bytes=%d%n", sequence, bytes);
    }
}
