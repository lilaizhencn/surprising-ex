package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class OrdinaryLifecycleOverlapTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void ordinaryChecksRetainAccountAndSymbolIsolationUntilLifecycleRetires(ProductLine line) {
        try (var core = new TradingCoreRuntime(line)) {
            var liquidation = new MatchingCommandAdmission.LifecycleScope(false, 7, "1", 91, true, false);
            core.admissions.pendingLifecycleScopes.put(91, List.of(liquidation));
            for (int cycle = 0; cycle < 3; cycle++) {
                assertThatThrownBy(() -> check(core, 7, "1"))
                        .isInstanceOf(CoreStateRejectedException.class);
                assertThatCode(() -> check(core, 8, "1")).doesNotThrowAnyException();
                assertThatCode(() -> check(core, 7, "2")).doesNotThrowAnyException();
                assertThat(core.admissions.pendingLifecycleScopes.get(91).getFirst()).isSameAs(liquidation);
            }
            core.admissions.pendingLifecycleScopes.remove(91);
            assertThatCode(() -> check(core, 7, "1")).doesNotThrowAnyException();
            core.admissions.pendingLifecycleScopes.put(92,
                    List.of(new MatchingCommandAdmission.LifecycleScope(true, 0, "1", 92, true, false)));
            assertThatThrownBy(() -> check(core, 8, "1")).isInstanceOf(CoreStateRejectedException.class);
            assertThatCode(() -> check(core, 8, "2")).doesNotThrowAnyException();
            core.admissions.pendingLifecycleScopes.remove(92);
            assertThatCode(() -> check(core, 8, "1")).doesNotThrowAnyException();
        }
    }

    private static void check(TradingCoreRuntime core, long userId, String symbol) {
        var message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, new UUID(95, userId),
                core.productLine, CommandSource.GATEWAY, 95, userId, userId, 1, 1),
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100, symbol, CoreOrderSide.BUY,
                        100, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                        CoreTimeInForce.GTC, false, "ordinary")));
        core.admissions.rejectLifecycleOverlap(message, CommandSlot.Operation.PLACE, core.decodeMatchingCommand(message));
    }
}
