package com.surprising.aeron.controller;

import io.aeron.cluster.service.Cluster;

/** 监听 Aeron Cluster 节点角色切换的监听器接口。 */
@FunctionalInterface
public interface ClusterRoleChangeListener {

    /** 当集群节点发生角色变化（如 Follower 成为 Leader，或 Leader 退位）时触发回调。 */
    void onRoleChanged(Cluster.Role newRole);
}
