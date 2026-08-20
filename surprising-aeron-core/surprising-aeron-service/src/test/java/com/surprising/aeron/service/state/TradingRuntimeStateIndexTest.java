package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import org.junit.jupiter.api.Test;

class TradingRuntimeStateIndexTest {

    @Test
    void keepsPositionKeysOrderedByUserAndSymbol() {
        TradingRuntimeState runtime = new TradingRuntimeState();
        runtime.putPosition(20, position(7, 1));
        runtime.putPosition(10, position(7, 1));
        runtime.putPosition(30, position(8, 1));

        assertThat(runtime.positionKeysForUserAndSymbol(7, 1)).containsExactly(10L, 20L);

        runtime.removePosition(10, 7);
        assertThat(runtime.positionKeysForUserAndSymbol(7, 1)).containsExactly(20L);
    }

    private static PositionRuntime position(long userId, int symbolId) {
        return new PositionRuntime(userId, symbolId, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                1, 1, 100, 100, 0, 10);
    }
}
