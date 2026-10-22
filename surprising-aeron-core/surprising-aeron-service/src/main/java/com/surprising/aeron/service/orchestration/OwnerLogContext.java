package com.surprising.aeron.service.orchestration;

import lombok.AllArgsConstructor;
import io.aeron.cluster.service.Cluster;
import org.agrona.concurrent.IdleStrategy;

/**
 *  Owner持有的角色、日志时间和日志位置；Aeron会话、发布器和定时器只存在于Cluster服务线程。
 * 构造约定：给 Owner 创建日志边界信息，不把 Aeron 会话或 Publication 交给交易线程。
 * 参数 role：启动时的集群角色，例如 Leader 或 Follower
 * 参数 timestamp：启动时记录的 Cluster 业务时间戳
 * 参数 position：启动时记录的复制日志位置
 * 参数 idle：边界等待时使用的空闲策略，不能借它回调 Cluster 服务
 */
@AllArgsConstructor
public final class OwnerLogContext {
    /** 仅Owner按输入FIFO更新。 */
    Cluster.Role role;
    /** timestamp 是复制日志的业务时间戳，position 是对应日志位置；都随输入顺序推进。 */
    long timestamp, position;
    /** 边界内等待只检查完成状态，不回调Aeron服务。 */
    final IdleStrategy idle;

}
