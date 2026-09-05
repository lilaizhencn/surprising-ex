package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductLineClusterLayoutTest {

    @Test
    void givesEveryProductLineAnIsolatedClusterAndPortRange() {
        Set<Integer> clusterIds = new HashSet<>();
        Set<Integer> clientPorts = new HashSet<>();

        for (ProductLine productLine : ProductLine.values()) {
            assertThat(clusterIds.add(ProductLineClusterLayout.clusterId(productLine))).isTrue();
            for (int memberId = 0; memberId < ProductLineClusterLayout.MEMBER_COUNT; memberId++) {
                assertThat(clientPorts.add(ProductLineClusterLayout.port(productLine, memberId,
                        ProductLineClusterLayout.CLIENT_FACING_OFFSET))).isTrue();
            }
        }

        assertThat(ProductLineClusterLayout.ingressEndpoints(ProductLine.SPOT,
                List.of("node0", "node1", "node2")))
                .isEqualTo("0=node0:20002,1=node1:20102,2=node2:20202");
    }
}
