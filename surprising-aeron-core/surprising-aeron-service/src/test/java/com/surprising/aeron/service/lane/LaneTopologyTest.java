package com.surprising.aeron.service.lane;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 固定拓扑默认值、有限配置选择及给定非法输入的构造校验测试。
 */
class LaneTopologyTest {

    /**
     * 核对默认路由版本为三、一个 Matcher、零独立风险线程、两个账户 Lane 及单分片掩码。
     */
    @Test
    void productionDefaultsUseVersionedMatcherRiskAndAccountLanes() {
        // 本例生产默认拓扑，用于核对实际数量或路由
        LaneTopology topology = LaneTopology.productionDefault();

        assertThat(topology.routeVersion()).isEqualTo(3);
        assertThat(topology.matchingEngineCount()).isEqualTo(1);
        assertThat(topology.riskEngineCount()).isZero();
        assertThat(topology.matcherShardMask()).isZero();
        assertThat(topology.accountLaneCount()).isEqualTo(2);
    }

    /**
     * 分别把账户数配置为二和八，核对实际拓扑读取，并在 finally 恢复原属性。
     */
    @Test
    void accountLaneCountIsConfigurable() {
        // 账户 Lane 数量配置的 JVM 属性名
        String key = "surprising.aeron.account-lanes";
        // 测试前该属性原值，finally 恢复或清除
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "2");
            assertThat(LaneTopology.configured(false).accountLaneCount()).isEqualTo(2);
            System.setProperty(key, "8");
            assertThat(LaneTopology.configured(false).accountLaneCount()).isEqualTo(8);
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    /**
     * 单 Matcher 下两个标的都路由零，重复用户路由保持一致且账户掩码只有一位；未验证多分片分布。
     */
    @Test
    void stableIdsRouteDeterministically() {
        // 本例生产默认拓扑，用于核对实际数量或路由
        LaneTopology topology = LaneTopology.productionDefault();

        assertThat(topology.matcherShardId(101)).isZero();
        assertThat(topology.matcherShardId(202)).isZero();
        assertThat(topology.accountLaneId(9_001)).isEqualTo(topology.accountLaneId(9_001));
        assertThat(Long.bitCount(topology.accountLaneMask(9_001))).isEqualTo(1);
    }

    /**
     * 对给定三组旧版本或不合法拓扑组合断言构造拒绝，不据此宣称逐个隔离了每个字段的拒绝原因。
     */
    @Test
    void rejectsNonPowerOfTwoOrOldRouteTopology() {
        assertThatThrownBy(() -> new LaneTopology(1, 4, 1, 3, 4,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 4, 4, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LaneTopology(3, 3, 1, 2, 4,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 4, 4, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LaneTopology(3, 4, 1, 3, 3,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 4, 4, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
