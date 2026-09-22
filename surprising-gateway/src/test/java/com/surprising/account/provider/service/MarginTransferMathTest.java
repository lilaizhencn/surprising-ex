package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.account.provider.model.ContractSpec;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.trading.api.model.OrderSide;
import org.junit.jupiter.api.Test;

class MarginTransferMathTest {

    @Test
    void separatesOpenAndCloseSteps() {
        assertThat(MarginTransferMath.closeSteps(10L, OrderSide.SELL, 4L)).isEqualTo(4L);
        assertThat(MarginTransferMath.openSteps(10L, OrderSide.SELL, 4L)).isZero();
        assertThat(MarginTransferMath.closeSteps(5L, OrderSide.SELL, 8L)).isEqualTo(5L);
        assertThat(MarginTransferMath.openSteps(5L, OrderSide.SELL, 8L)).isEqualTo(3L);
        assertThat(MarginTransferMath.openSteps(-5L, OrderSide.SELL, 2L)).isEqualTo(2L);
    }

    @Test
    void consumesOnlyAvailableOrderMargin() {
        assertThat(MarginTransferMath.orderMarginConsumeAmount(1000L, 400L, 0L, 10L, 6L, false))
                .isEqualTo(600L);
        assertThat(MarginTransferMath.orderMarginConsumeAmount(1000L, 400L, 500L, 10L, 6L, false))
                .isEqualTo(100L);
    }

    @Test
    void roundsOpeningReservationAllocationUpForPartialFills() {
        assertThat(MarginTransferMath.orderMarginConsumeAmount(101L, 0L, 0L, 10L, 1L, false))
                .isEqualTo(11L);
    }

    @Test
    void sweepsRoundingRemainderOnTerminalOpenFill() {
        assertThat(MarginTransferMath.orderMarginConsumeAmount(100L, 0L, 66L, 3L, 1L, true))
                .isEqualTo(34L);
    }

    @Test
    void calculatesActualOpeningMarginFromFillPrice() {
        ContractSpec spec = new ContractSpec(1L, ContractType.LINEAR_PERPETUAL, "USDT",
                100L, 1L, 1L, 10_000L, 0L, 0L);

        assertThat(MarginTransferMath.openingInitialMarginUnits(spec, 100L, 6L)).isEqualTo(600L);
    }

    @Test
    void calculatesExcessReservedMarginForRelease() {
        assertThat(MarginTransferMath.excessOrderMarginUnits(660L, 600L)).isEqualTo(60L);
        assertThatThrownBy(() -> MarginTransferMath.excessOrderMarginUnits(600L, 660L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserved order margin");
    }

    @Test
    void releasesPositionMarginProportionally() {
        assertThat(MarginTransferMath.positionMarginReleaseAmount(1000L, 4L, 10L)).isEqualTo(400L);
        assertThat(MarginTransferMath.positionMarginReleaseAmount(1000L, 10L, 10L)).isEqualTo(1000L);
    }
}
