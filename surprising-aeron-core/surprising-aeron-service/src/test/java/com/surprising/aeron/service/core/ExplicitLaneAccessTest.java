package com.surprising.aeron.service.core;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 六产品线逐一检查显式 Lane 访问门禁：运行时实例、用户路由及活跃线程所有权必须都匹配；实际账户查询作用域内可读本用户，不能顺带读取另一 Lane 用户。
 */
class ExplicitLaneAccessTest {
    /**
     * 建立同产品线两个独立运行时，先拒绝外部实例 Lane 和错误用户；启动账户线程后拒绝 Owner 直接读，再派发真实查询核对本用户余额及跨实例、跨用户拒绝，五秒内等到回执。
     * @param product 本轮独立验证的产品线，参数化遍历全部六条
     */
    @ParameterizedTest @EnumSource(ProductLine.class)
    void explicitLaneRejectsOtherRuntimeOtherUserAndActiveOwner(ProductLine product) throws Exception {
        try (/* 本次真正持有用户七余额并启动账户工作线程的运行时 */ var runtime = RuntimeStateProjector.project(TradingCoreState.empty(product), new RuntimeIdentityRegistry());
             /* 同产品线另一独立运行时，用来构造跨实例 Lane */ var other = RuntimeStateProjector.project(TradingCoreState.empty(product), new RuntimeIdentityRegistry())) {
            // 原运行时用户七所属的实际账户 Lane
            var lane = runtime.accountLaneForUser(7);
            // 另一运行时用户七的 Lane，编号相同也不能当成原实例使用
            var foreign = other.accountLaneForUser(7);
            assertThatThrownBy(() -> runtime.requireLaneAccess(foreign, 7)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.enterLaneCommandScope(foreign)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.order(foreign, 1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.reservation(foreign, 1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.position(foreign, 1)).isInstanceOf(IllegalStateException.class);
            // 从八开始寻找路由到另一 Lane 的用户候选
            long otherLaneUser = 8;
            while (runtime.accountLaneId(otherLaneUser) == runtime.accountLaneId(7)) otherLaneUser++;
            // 最终确定的另一 Lane 用户，供异步断言捕获
            final long foreignUser = otherLaneUser;
            assertThatThrownBy(() -> runtime.requireLaneAccess(lane, foreignUser)).isInstanceOf(IllegalStateException.class);
            runtime.putBalance(new BalanceRuntime(7, 0, 100, 0));
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            assertThatThrownBy(() -> runtime.accountLaneForUser(7)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.order(lane, 1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.position(lane, 1)).isInstanceOf(IllegalStateException.class);
            // 真实账户查询执行结束后通知测试线程的门闩
            var done = new CountDownLatch(1);
            // 把账户查询线程的异常交回测试线程的原子槽
            var failure = new AtomicReference<Throwable>();
            assertThat(runtime.dispatchAccountQuery(1L << lane.laneId(), /* 查询调度实际进入的原运行时所属 Lane */ actual -> {
                try {
                    runtime.readAccountLane(actual, /* 在受保护的账户读取作用域内提供的 Lane，只允许读取它负责的用户 */ scoped -> {
                        assertThat(scoped).isSameAs(lane);
                        assertThat(runtime.balance(scoped, 7, 0).availableUnits()).isEqualTo(100);
                        assertThatThrownBy(() -> runtime.balance(scoped, foreignUser, 0))
                                .isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(() -> runtime.requireLaneAccess(foreign, 7))
                                .isInstanceOf(IllegalStateException.class);
                        return null;
                    });
                } catch (/* 账户读取回调发生的原异常，保存后由测试线程断言 */ Throwable problem) { failure.set(problem); }
                finally { done.countDown(); }
            })).isTrue();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
            runtime.assertAccountLanesHealthy();
        }
    }
}
