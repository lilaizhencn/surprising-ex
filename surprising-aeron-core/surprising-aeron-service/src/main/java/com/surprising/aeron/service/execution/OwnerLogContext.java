package com.surprising.aeron.service.execution;

import io.aeron.*;
import io.aeron.cluster.service.*;
import io.aeron.logbuffer.BufferClaim;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;

/** Owner持有的日志元数据；不能从交易线程访问Aeron会话、发布器或定时器API。 */
final class OwnerLogContext implements Cluster {
    /** 初始化时复制的节点标识、集群时间单位。 */
    private final int memberId;
    private final TimeUnit unit;
    /** 仅Owner按输入FIFO更新的角色、日志时间和位置。 */
    Role role;
    long timestamp, position;
    /** 边界内等待只检查完成状态，不回调Aeron服务。 */
    private final IdleStrategy idle = new BusySpinIdleStrategy();

    OwnerLogContext(int memberId, TimeUnit unit, Role role, long timestamp, long position) {
        this.memberId = memberId; this.unit = unit; this.role = role;
        this.timestamp = timestamp; this.position = position;
    }
    public int memberId() { return memberId; }
    public Role role() { return role; }
    public long logPosition() { return position; }
    public long time() { return timestamp; }
    public TimeUnit timeUnit() { return unit; }
    public IdleStrategy idleStrategy() { return idle; }
    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Aeron API is confined to the cluster service thread");
    }
    public Aeron aeron() { throw unsupported(); }
    public ClusteredServiceContainer.Context context() { throw unsupported(); }
    public ClientSession getClientSession(long id) { throw unsupported(); }
    public Collection<ClientSession> clientSessions() { throw unsupported(); }
    public void forEachClientSession(Consumer<? super ClientSession> action) { throw unsupported(); }
    public boolean closeClientSession(long id) { throw unsupported(); }
    public boolean scheduleTimer(long id, long deadline) { throw unsupported(); }
    public boolean cancelTimer(long id) { throw unsupported(); }
    public long offer(DirectBuffer buffer, int offset, int length) { throw unsupported(); }
    public long offer(DirectBufferVector[] vectors) { throw unsupported(); }
    public long tryClaim(int length, BufferClaim claim) { throw unsupported(); }
}
