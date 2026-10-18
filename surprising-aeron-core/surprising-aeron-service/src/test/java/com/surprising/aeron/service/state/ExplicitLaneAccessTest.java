package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class ExplicitLaneAccessTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void explicitLaneRejectsOtherRuntimeOtherUserAndActiveOwner(ProductLine product) throws Exception {
        try (var runtime = RuntimeStateProjector.project(TradingCoreState.empty(product), new RuntimeIdentityRegistry());
             var other = RuntimeStateProjector.project(TradingCoreState.empty(product), new RuntimeIdentityRegistry())) {
            var lane = runtime.accountLaneForUser(7);
            var foreign = other.accountLaneForUser(7);
            assertThatThrownBy(() -> runtime.requireLaneAccess(foreign, 7)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.enterLaneCommandScope(foreign)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.order(foreign, 1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.reservation(foreign, 1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.position(foreign, 1)).isInstanceOf(IllegalStateException.class);
            long otherLaneUser = 8;
            while (runtime.accountLaneId(otherLaneUser) == runtime.accountLaneId(7)) otherLaneUser++;
            final long foreignUser = otherLaneUser;
            assertThatThrownBy(() -> runtime.requireLaneAccess(lane, foreignUser)).isInstanceOf(IllegalStateException.class);
            runtime.putBalance(new BalanceRuntime(7, 0, 100, 0));
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            assertThatThrownBy(() -> runtime.accountLaneForUser(7)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.order(lane, 1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.position(lane, 1)).isInstanceOf(IllegalStateException.class);
            var done = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            assertThat(runtime.dispatchAccountQuery(1L << lane.laneId(), actual -> {
                try {
                    runtime.readAccountLane(actual, scoped -> {
                        assertThat(scoped).isSameAs(lane);
                        assertThat(runtime.balance(scoped, 7, 0).availableUnits()).isEqualTo(100);
                        assertThatThrownBy(() -> runtime.balance(scoped, foreignUser, 0))
                                .isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(() -> runtime.requireLaneAccess(foreign, 7))
                                .isInstanceOf(IllegalStateException.class);
                        return null;
                    });
                } catch (Throwable problem) { failure.set(problem); }
                finally { done.countDown(); }
            })).isTrue();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
            runtime.assertAccountLanesHealthy();
        }
    }
}
