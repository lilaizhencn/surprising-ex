package com.surprising.aeron.service.state.account;

import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

/**
 * 按六产品线核对终态订单释放共享及多个客户别名时的引用计数、跨字典保护和索引清理，保留仍被另一订单引用的客户号。
 */
class TerminalClientReleaseTest {
    /**
     * 为两笔订单设置共享与额外别名，先尝试错误字典释放，再逐订单清理，检查共享引用、零别名与重建新身份的生命周期。
     * @param product 当前枚举到的独立产品线
     */
    @ParameterizedTest @EnumSource(ProductLine.class)
    void sharedAndMultipleAliasesSurviveFailureThenReleaseExactlyOnce(ProductLine product) {
        // 本产品运行时真实绑定的客户身份字典。
        var identities = new RuntimeIdentityRegistry();
        // 故意来自另一个运行时的字典，传入本 Lane 释放时应被拒绝。
        var foreign = new RuntimeIdentityRegistry();
        try (/* 当前产品由空状态投影的本运行时，字典在此绑定实际 Lane。 */ var runtime = RuntimeStateProjector.project(TradingCoreState.empty(product), identities);
             /* 绑定外部字典的另一个运行时，用于验证跨运行时字典拒绝边界。 */ var other = RuntimeStateProjector.project(TradingCoreState.empty(product), foreign)) {
            // 本运行时中用户七实际所属的账户 Lane。
            var lane = runtime.accountLaneForUser(7);
            // 客户号 shared 的正数身份键，被两笔订单共同引用。
            long first = RuntimeIdentityRegistry.clientKeyValue(identities.prepareClientKeyInLane(lane, 7, "shared"));
            identities.prepareClientKeyInLane(lane, 7, "shared");
            // 客户号 other 的正数身份键，只由订单十二额外持有。
            long second = RuntimeIdentityRegistry.clientKeyValue(identities.prepareClientKeyInLane(lane, 7, "other"));
            lane.clientKeysByOrderId.add(11, first);
            lane.clientKeysByOrderId.add(12, first);
            lane.clientKeysByOrderId.add(12, second);
            lane.clientKeysByOrderId.add(12, 0);
            // 用户客户键到订单编号的原语表，共享客户键已指向后来的订单十二。
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
            // 旧别名全部释放后新建客户号 next 的正数身份键。
            long next = RuntimeIdentityRegistry.clientKeyValue(identities.prepareClientKeyInLane(lane, 7, "next"));
            lane.clientKeysByOrderId.add(13, next);
            assertThat(identities.clientOrderId(7, next)).isEqualTo("next");
            lane.releaseOrderClientIdentities(identities, 13, 7);
            lane.removeClientOrdersForOrder(7, 13);
            assertThat(identities.clientIdentityCount()).isZero();
        }
    }
}
