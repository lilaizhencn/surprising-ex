package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreFeePolicySnapshotCodecTest {

    @Test
    void roundTripsPoliciesInStableIdOrder() {
        Map<Long, CoreFeePolicyState> policies = Map.of(
                9L, new CoreFeePolicyState(9, 3, 1001, "", -10, 25, 4, true, 1_000, 0),
                7L, new CoreFeePolicyState(7, 2, 1001, "BTC-USDT", -25, 75, 0, true, 900, 2_000));

        byte[] first = CoreFeePolicySnapshotCodec.encode(policies);
        byte[] second = CoreFeePolicySnapshotCodec.encode(Map.of(7L, policies.get(7L), 9L, policies.get(9L)));

        assertThat(second).containsExactly(first);
        assertThat(CoreFeePolicySnapshotCodec.decode(first)).isEqualTo(policies);
    }
}
