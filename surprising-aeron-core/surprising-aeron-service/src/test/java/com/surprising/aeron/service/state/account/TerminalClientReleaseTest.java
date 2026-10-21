package com.surprising.aeron.service.state.account;

import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class TerminalClientReleaseTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void sharedAndMultipleAliasesSurviveFailureThenReleaseExactlyOnce(ProductLine product) {
        var identities = new RuntimeIdentityRegistry();
        var foreign = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(TradingCoreState.empty(product), identities);
             var other = RuntimeStateProjector.project(TradingCoreState.empty(product), foreign)) {
            var lane = runtime.accountLaneForUser(7);
            long first = RuntimeIdentityRegistry.clientKeyValue(identities.prepareClientKeyInLane(lane, 7, "shared"));
            identities.prepareClientKeyInLane(lane, 7, "shared");
            long second = RuntimeIdentityRegistry.clientKeyValue(identities.prepareClientKeyInLane(lane, 7, "other"));
            lane.clientKeysByOrderId.add(11, first);
            lane.clientKeysByOrderId.add(12, first);
            lane.clientKeysByOrderId.add(12, second);
            lane.clientKeysByOrderId.add(12, 0);
            var clientOrders = new org.agrona.collections.Long2LongHashMap(0);
            clientOrders.put(first, 12);
            clientOrders.put(second, 12);
            lane.clientOrderIndex.put(7, clientOrders);
            assertThatThrownBy(() -> lane.releaseOrderClientIdentities(foreign, 11, 7))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(identities.clientIdentityCount()).isEqualTo(2);
            assertThat(foreign.clientIdentityCount()).isZero();
            lane.releaseOrderClientIdentities(identities, 11, 7);
            lane.removeClientOrdersForOrder(7, 11);
            assertThat(identities.clientOrderId(7, first)).isEqualTo("shared");
            assertThat(clientOrders.get(first)).isEqualTo(12);
            lane.releaseOrderClientIdentities(identities, 12, 7);
            lane.removeClientOrdersForOrder(7, 12);
            assertThat(identities.clientIdentityCount()).isZero();
            assertThat(clientOrders.isEmpty()).isTrue();
            long next = RuntimeIdentityRegistry.clientKeyValue(identities.prepareClientKeyInLane(lane, 7, "next"));
            lane.clientKeysByOrderId.add(13, next);
            assertThat(identities.clientOrderId(7, next)).isEqualTo("next");
            lane.releaseOrderClientIdentities(identities, 13, 7);
            lane.removeClientOrdersForOrder(7, 13);
            assertThat(identities.clientIdentityCount()).isZero();
        }
    }
}
