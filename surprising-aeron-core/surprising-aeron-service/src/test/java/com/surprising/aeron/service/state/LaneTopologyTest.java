package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LaneTopologyTest {

    @Test
    void productionDefaultsUseVersionedMatcherRiskAndAccountLanes() {
        LaneTopology topology = LaneTopology.productionDefault();

        assertThat(topology.routeVersion()).isEqualTo(3);
        assertThat(topology.matchingEngineCount()).isEqualTo(1);
        assertThat(topology.riskEngineCount()).isZero();
        assertThat(topology.matcherShardMask()).isZero();
        assertThat(topology.accountLaneCount()).isEqualTo(4);
        assertThat(topology.topologyHash()).isNotZero();
    }

    @Test
    void stableIdsRouteDeterministicallyAndContributeToTheSnapshotHash() {
        LaneTopology topology = LaneTopology.productionDefault();
        Map<String, Integer> symbols = Map.of("BTC-USDT", 101, "ETH-USDT", 202);

        assertThat(topology.matcherShardId(101)).isZero();
        assertThat(topology.matcherShardId(202)).isZero();
        assertThat(topology.accountLaneId(9_001)).isEqualTo(topology.accountLaneId(9_001));
        assertThat(Long.bitCount(topology.accountLaneMask(9_001))).isEqualTo(1);
        assertThat(topology.symbolRouteHash(symbols)).isNotZero();
        assertThat(topology.symbolRouteHash(symbols))
                .isEqualTo(topology.symbolRouteHash(Map.of("ETH-USDT", 202, "BTC-USDT", 101)));
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
