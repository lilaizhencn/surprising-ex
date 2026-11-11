package com.surprising.aeron.model;

import io.aeron.cluster.service.Cluster;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/** Owner持有的角色、日志时间和日志位置。单线程内联模式下直接随复制日志顺序推进。 */
@AllArgsConstructor
@Getter
@Accessors(fluent = true, chain = false)
public final class OwnerLogContext {
    @Setter
    private Cluster.Role role;
    @Setter
    private long timestamp, position;
}
