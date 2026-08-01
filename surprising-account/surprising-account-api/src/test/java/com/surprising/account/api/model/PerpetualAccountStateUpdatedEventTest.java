package com.surprising.account.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerpetualAccountStateUpdatedEventTest {

    @Test
    void normalizesAndKeepsCompleteSnapshotFields() {
        var event = new PerpetualAccountStateUpdatedEvent(
                1, 11L, 7L, ProductLine.LINEAR_PERPETUAL, 1001L, "usdt_perpetual",
                List.of(new PerpetualAccountStateUpdatedEvent.Balance("usdt", 90L, 10L)),
                List.of(new PerpetualAccountStateUpdatedEvent.Deficit("usdt", 5L, 2L)),
                List.of(new PerpetualAccountStateUpdatedEvent.Position("btc-usdt", 3L, MarginMode.CROSS,
                        PositionSide.NET, 2L, 100L, 200L, 0L, Instant.EPOCH)),
                List.of(new PerpetualAccountStateUpdatedEvent.PositionMargin("btc-usdt", "usdt",
                        MarginMode.ISOLATED, PositionSide.NET, 20L)),
                List.of(new PerpetualAccountStateUpdatedEvent.OrderLock("usdt", 30L)),
                PositionMode.ONE_WAY, Instant.EPOCH, "trace");

        assertThat(event.accountType()).isEqualTo("USDT_PERPETUAL");
        assertThat(event.balances().getFirst().asset()).isEqualTo("USDT");
        assertThat(event.positions().getFirst().symbol()).isEqualTo("BTC-USDT");
        assertThat(event.partitionKey()).isEqualTo("LINEAR_PERPETUAL:1001");
    }

    @Test
    void rejectsIncompleteOpenPosition() {
        assertThatThrownBy(() -> new PerpetualAccountStateUpdatedEvent.Position(
                "BTC-USDT", 0L, MarginMode.CROSS, PositionSide.NET,
                1L, 100L, 100L, 0L, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("open position fields");
    }
}
