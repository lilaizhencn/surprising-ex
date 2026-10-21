package com.surprising.aeron.service.orchestration;

import io.aeron.cluster.service.Cluster;
import org.agrona.concurrent.IdleStrategy;

/** Owner持有的角色、日志时间和日志位置；Aeron会话、发布器和定时器只存在于Cluster服务线程。 */
public final class OwnerLogContext {
    /** 仅Owner按输入FIFO更新。 */
    Cluster.Role role;
    long timestamp, position;
    /** 边界内等待只检查完成状态，不回调Aeron服务。 */
    final IdleStrategy idle;

    public OwnerLogContext(Cluster.Role role, long timestamp, long position, IdleStrategy idle) {
        this.role = role;
        this.timestamp = timestamp;
        this.position = position;
        this.idle = idle;
    }
}
