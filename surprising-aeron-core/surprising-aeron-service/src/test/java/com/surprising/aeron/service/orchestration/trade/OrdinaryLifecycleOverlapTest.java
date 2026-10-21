package com.surprising.aeron.service.orchestration.trade;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

/**
 * 跨六产品线检查普通订单与在途强平、全品种结算的资源冲突：同用户同品种受阻，其他账户或品种保持隔离，生命周期退休后解除限制。
 */
class OrdinaryLifecycleOverlapTest {
    /**
     * 手动登记强平范围并重复三次检查隔离，移除后允许原账户继续；再登记全品种结算范围，检查同品种其他用户也受阻而异品种不受影响。
     * @param line 当前参数化测试的产品线，枚举覆盖六产品线
     */
    @ParameterizedTest @EnumSource(ProductLine.class)
    void ordinaryChecksRetainAccountAndSymbolIsolationUntilLifecycleRetires(ProductLine line) {
        try (/* 当前产品线创建并自动关闭的 Core */ var core = new TradingCoreRuntime(line)) {
            // 用户七、品种一的在途强平范围，反复检查时仍应保留同一对象
            var liquidation = new MatchingCommandAdmission.LifecycleScope(false, 7, "1", 91, true, false);
            core.admissions().pendingLifecycleScopes.put(91, List.of(liquidation));
            for (/* 重复检查次数下标，验证冲突探测不会消耗原范围 */ int cycle = 0; cycle < 3; cycle++) {
                assertThatThrownBy(() -> check(core, 7, "1"))
                        .isInstanceOf(CoreStateRejectedException.class);
                assertThatCode(() -> check(core, 8, "1")).doesNotThrowAnyException();
                assertThatCode(() -> check(core, 7, "2")).doesNotThrowAnyException();
                assertThat(core.admissions().pendingLifecycleScopes.get(91).getFirst()).isSameAs(liquidation);
            }
            core.admissions().pendingLifecycleScopes.remove(91);
            assertThatCode(() -> check(core, 7, "1")).doesNotThrowAnyException();
            core.admissions().pendingLifecycleScopes.put(92,
                    List.of(new MatchingCommandAdmission.LifecycleScope(true, 0, "1", 92, true, false)));
            assertThatThrownBy(() -> check(core, 8, "1")).isInstanceOf(CoreStateRejectedException.class);
            assertThatCode(() -> check(core, 8, "2")).doesNotThrowAnyException();
            core.admissions().pendingLifecycleScopes.remove(92);
            assertThatCode(() -> check(core, 8, "1")).doesNotThrowAnyException();
        }
    }

    /**
     * 构造指定用户及品种的普通下单，只调用准入生命周期冲突检查；不进行资金预留、撮合或结算。
     * @param core 当前产品线的测试运行时
     * @param userId 本次模拟普通下单用户
     * @param symbol 本次模拟下单的品种名称
     */
    private static void check(TradingCoreRuntime core, long userId, String symbol) {
        // 仅用于普通订单生命周期检查的原始 Core 消息
        var message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, new UUID(95, userId),
                core.productLine(), CommandSource.GATEWAY, 95, userId, userId, 1, 1),
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100, symbol, CoreOrderSide.BUY,
                        100, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                        CoreTimeInForce.GTC, false, "ordinary")));
        core.admissions().rejectLifecycleOverlap(message, CommandSlot.Operation.PLACE, core.decodeMatchingCommand(message));
    }
}
