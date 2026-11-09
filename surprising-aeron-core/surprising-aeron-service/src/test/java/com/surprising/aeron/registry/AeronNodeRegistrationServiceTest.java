package com.surprising.aeron.registry;

import com.surprising.aeron.config.ClusterTopology;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AeronNodeRegistrationServiceTest {

    @Test
    @DisplayName("验证 Aeron 节点注册服务元数据构造与角色变更流转")
    void shouldConstructMetadataAndHandleRoleChange() {
        ClusterTopology topology = new ClusterTopology(
                ProductLine.SPOT,
                0,
                List.of("127.0.0.1", "127.0.0.1", "127.0.0.1"),
                Path.of("target/test-data/aeron")
        );
        AeronRegistryProperties properties = new AeronRegistryProperties(
                false, // 测试中不实际向外部网络发送请求
                "127.0.0.1:8848",
                "surprising-aeron-core",
                "DEFAULT_GROUP",
                "DEFAULT",
                "192.168.1.10"
        );

        AeronNodeRegistrationService service = new AeronNodeRegistrationService(topology, properties);
        service.afterSingletonsInstantiated();

        // 验证角色切换正常流转且不抛出异常
        service.onRoleChanged(Cluster.Role.LEADER);
        service.onRoleChanged(Cluster.Role.FOLLOWER);

        service.destroy();
    }
}
