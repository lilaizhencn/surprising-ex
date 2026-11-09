package com.surprising.aeron.client.topology;

import com.surprising.product.api.ProductLine;
import java.util.List;

/**
 * 提供 Aeron 集群成员节点地址列表的拓扑提供者接口。
 */
@FunctionalInterface
public interface AeronClusterTopologyProvider {

    /**
     * 获取指定产品线的 Aeron 集群成员节点主机/IP 列表（按 nodeId 0, 1, 2 升序排列）。
     *
     * @param productLine 产品线
     * @return 成员节点地址列表（通常包含 1 个或 3 个主机名/IP）
     */
    List<String> lookupHostnames(ProductLine productLine);

    /**
     * 创建基于固定地址列表的静态提供者。
     */
    static AeronClusterTopologyProvider ofStatic(List<String> hostnames) {
        List<String> immutable = List.copyOf(hostnames);
        return productLine -> immutable;
    }
}
