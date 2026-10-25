package com.surprising.aeron.model;

import io.aeron.cluster.service.Cluster;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Owner持有的角色、日志时间和日志位置；单线程内联模式下直接随复制日志顺序推进。
 */
@AllArgsConstructor
@Getter
@Accessors(fluent = true, chain = false)
public final class OwnerLogContext {
    /** 集群角色。 */
    @Setter
    private Cluster.Role role;
    /** timestamp 是复制日志的业务时间戳，position 是对应日志位置。 */
    @Setter
    private long timestamp, position;
}
