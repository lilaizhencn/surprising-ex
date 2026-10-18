package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LaneTopologyTest {

    @Test
    void productionDefaultsUseVersionedMatcherRiskAndAccountLanes() {
        LaneTopology topology = LaneTopology.productionDefault();

        assertThat(topology.routeVersion()).isEqualTo(3);
        assertThat(topology.matchingEngineCount()).isEqualTo(1);
        assertThat(topology.riskEngineCount()).isZero();
        assertThat(topology.matcherShardMask()).isZero();
        assertThat(topology.accountLaneCount()).isEqualTo(2);
    }

    @Test
    void accountLaneCountIsConfigurable() {
        String key = "surprising.aeron.account-lanes";
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

    @Test
    void stableIdsRouteDeterministically() {
        LaneTopology topology = LaneTopology.productionDefault();

        assertThat(topology.matcherShardId(101)).isZero();
        assertThat(topology.matcherShardId(202)).isZero();
        assertThat(topology.accountLaneId(9_001)).isEqualTo(topology.accountLaneId(9_001));
        assertThat(Long.bitCount(topology.accountLaneMask(9_001))).isEqualTo(1);
    }

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
