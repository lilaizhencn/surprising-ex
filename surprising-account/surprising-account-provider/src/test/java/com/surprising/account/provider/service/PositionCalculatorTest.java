package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.PositionState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.trading.api.model.OrderSide;
import org.junit.jupiter.api.Test;

class PositionCalculatorTest {

    private final PositionCalculator calculator = new PositionCalculator();

    @Test
    void opensLongPosition() {
        var change = calculator.apply(new PositionState(0, 0, 0, 0), OrderSide.BUY, 100L, 5L, linear(), linear());

        assertThat(change.next()).isEqualTo(new PositionState(5L, 1L, 100L, 0L));
        assertThat(change.realizedPnlDeltaUnits()).isZero();
    }

    @Test
    void averagesSameDirectionPosition() {
        var change = calculator.apply(new PositionState(5L, 1L, 100L, 0L), OrderSide.BUY, 120L, 5L,
                linear(), linear());

        assertThat(change.next()).isEqualTo(new PositionState(10L, 1L, 110L, 0L));
    }

    @Test
    void partiallyClosesLongPosition() {
        var change = calculator.apply(new PositionState(10L, 1L, 100L, 0L), OrderSide.SELL, 130L, 4L,
                linear(), linear());

        assertThat(change.next()).isEqualTo(new PositionState(6L, 1L, 100L, 120L));
        assertThat(change.realizedPnlDeltaUnits()).isEqualTo(120L);
    }

    @Test
    void flipsLongToShort() {
        var change = calculator.apply(new PositionState(5L, 1L, 100L, 0L), OrderSide.SELL, 90L, 8L,
                linear(), linear());

        assertThat(change.next()).isEqualTo(new PositionState(-3L, 1L, 90L, -50L));
        assertThat(change.realizedPnlDeltaUnits()).isEqualTo(-50L);
    }

    @Test
    void closesShortWithProfit() {
        var change = calculator.apply(new PositionState(-10L, 1L, 100L, 0L), OrderSide.BUY, 80L, 10L,
                linear(), linear());

        assertThat(change.next()).isEqualTo(new PositionState(0L, 0L, 0L, 200L));
        assertThat(change.realizedPnlDeltaUnits()).isEqualTo(200L);
    }

    @Test
    void inverseUsesHarmonicAverageEntryPrice() {
        var change = calculator.apply(new PositionState(1L, 2L, 50_000L, 0L),
                OrderSide.BUY, 100_000L, 1L, inverse(), inverse());

        assertThat(change.next()).isEqualTo(new PositionState(2L, 2L, 66_667L, 0L));
    }

    @Test
    void inverseRealizedPnlSettlesInCoinUnits() {
        var change = calculator.apply(new PositionState(1L, 2L, 50_000L, 0L),
                OrderSide.SELL, 60_000L, 1L, inverse(), inverse());

        assertThat(change.next()).isEqualTo(new PositionState(0L, 0L, 0L, 33_333L));
        assertThat(change.realizedPnlDeltaUnits()).isEqualTo(33_333L);
    }

    @Test
    void flipUsesFillInstrumentVersionForNewExposure() {
        ContractSpec nextVersion = new ContractSpec(3L, ContractType.LINEAR_PERPETUAL,
                "USDT", 1L, 100_000_000L, 100_000_000L, 0L, 0L);

        var change = calculator.apply(new PositionState(5L, 1L, 100L, 0L),
                OrderSide.SELL, 90L, 8L, linear(), nextVersion);

        assertThat(change.next()).isEqualTo(new PositionState(-3L, 3L, 90L, -50L));
    }

    @Test
    void rejectsSameDirectionFillFromDifferentInstrumentVersion() {
        ContractSpec nextVersion = new ContractSpec(3L, ContractType.LINEAR_PERPETUAL,
                "USDT", 1L, 100_000_000L, 100_000_000L, 0L, 0L);

        assertThatThrownBy(() -> calculator.apply(new PositionState(5L, 1L, 100L, 0L),
                OrderSide.BUY, 120L, 1L, linear(), nextVersion))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different instrument versions");
    }

    @Test
    void rejectsMinimumSignedQuantityInsteadOfOverflowingAbsoluteValue() {
        assertThatThrownBy(() -> calculator.apply(new PositionState(Long.MIN_VALUE, 1L, 100L, 0L),
                OrderSide.BUY, 120L, 1L, linear(), linear()))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void rejectsSameDirectionQuantityOverflow() {
        assertThatThrownBy(() -> calculator.apply(new PositionState(Long.MAX_VALUE, 1L, 100L, 0L),
                OrderSide.BUY, 100L, 1L, linear(), linear()))
                .isInstanceOf(ArithmeticException.class);
    }

    private ContractSpec linear() {
        return new ContractSpec(1L, ContractType.LINEAR_PERPETUAL, "USDT", 1L,
                100_000_000L, 100_000_000L, 0L, 0L);
    }

    private ContractSpec inverse() {
        return new ContractSpec(2L, ContractType.INVERSE_PERPETUAL, "BTC",
                10_000_000_000L, 100_000_000L, 100_000_000L, 0L, 0L);
    }
}
