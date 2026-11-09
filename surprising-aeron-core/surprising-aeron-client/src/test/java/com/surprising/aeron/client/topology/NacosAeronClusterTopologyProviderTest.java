package com.surprising.aeron.client.topology;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class NacosAeronClusterTopologyProviderTest {

    @Test
    @DisplayName("当 Nacos 无实例时应平滑回退到 fallback 静态配置")
    void shouldFallbackWhenNoInstances() throws Exception {
        NamingService namingService = Mockito.mock(NamingService.class);
        when(namingService.selectInstances(eq("surprising-aeron-core"), eq("DEFAULT_GROUP"), anyBoolean()))
                .thenReturn(List.of());

        AeronClusterTopologyProvider fallback = AeronClusterTopologyProvider.ofStatic(List.of("127.0.0.1"));
        try (NacosAeronClusterTopologyProvider provider = new NacosAeronClusterTopologyProvider(
                namingService, false, "surprising-aeron-core", "DEFAULT_GROUP", fallback)) {
            List<String> hostnames = provider.lookupHostnames(ProductLine.SPOT);
            assertThat(hostnames).containsExactly("127.0.0.1");
        }
    }

    @Test
    @DisplayName("当 Nacos 存在 3 节点实例时应按 nodeId 升序解析主机列表")
    void shouldResolveThreeNodesOrderedByNodeId() throws Exception {
        NamingService namingService = Mockito.mock(NamingService.class);

        Instance node2 = new Instance();
        node2.setIp("10.0.0.3");
        node2.setMetadata(Map.of("productLine", "SPOT", "nodeId", "2"));

        Instance node0 = new Instance();
        node0.setIp("10.0.0.1");
        node0.setMetadata(Map.of("productLine", "SPOT", "nodeId", "0"));

        Instance node1 = new Instance();
        node1.setIp("10.0.0.2");
        node1.setMetadata(Map.of("productLine", "SPOT", "nodeId", "1"));

        // 打乱顺序模拟 Nacos 返回
        when(namingService.selectInstances(eq("surprising-aeron-core"), eq("DEFAULT_GROUP"), anyBoolean()))
                .thenReturn(List.of(node2, node0, node1));

        AeronClusterTopologyProvider fallback = AeronClusterTopologyProvider.ofStatic(List.of("127.0.0.1"));
        try (NacosAeronClusterTopologyProvider provider = new NacosAeronClusterTopologyProvider(
                namingService, false, "surprising-aeron-core", "DEFAULT_GROUP", fallback)) {
            List<String> hostnames = provider.lookupHostnames(ProductLine.SPOT);
            assertThat(hostnames).containsExactly("10.0.0.1", "10.0.0.2", "10.0.0.3");
        }
    }
}
