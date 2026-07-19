package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.PositionChange;
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
    void linearRoundTripConservesPnlWhenAveragePriceHasRemainder() {
        PositionState state = calculator.apply(new PositionState(0L, 0L, 0L, 0L), OrderSide.BUY, 100L, 1L,
                linear(), linear()).next();
        state = calculator.apply(state, OrderSide.BUY, 101L, 2L, linear(), linear()).next();

        assertThat(state).isEqualTo(new PositionState(3L, 1L, 100L, 302L, 0L));

        PositionChange firstClose = calculator.apply(state, OrderSide.SELL, 100L, 1L, linear(), linear());
        PositionChange secondClose = calculator.apply(firstClose.next(), OrderSide.SELL, 101L, 2L,
                linear(), linear());

        assertThat(firstClose.realizedPnlDeltaUnits()).isZero();
        assertThat(firstClose.next()).isEqualTo(new PositionState(2L, 1L, 101L, 202L, 0L));
        assertThat(secondClose.realizedPnlDeltaUnits()).isZero();
        assertThat(secondClose.next()).isEqualTo(new PositionState(0L, 1L, 0L, 0L, 0L));
        assertThat(firstClose.realizedPnlDeltaUnits() + secondClose.realizedPnlDeltaUnits()).isZero();
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

        assertThat(change.next()).isEqualTo(new PositionState(0L, 1L, 0L, 200L));
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

        assertThat(change.next()).isEqualTo(new PositionState(0L, 2L, 0L, 33_333L));
        assertThat(change.realizedPnlDeltaUnits()).isEqualTo(33_333L);
    }

    @Test
    void deliveryContractsReuseLinearAndInversePositionRules() {
        var linearChange = calculator.apply(new PositionState(10L, 4L, 100L, 0L),
                OrderSide.SELL, 130L, 4L, linearDelivery(), linearDelivery());
        var inverseChange = calculator.apply(new PositionState(1L, 5L, 50_000L, 0L),
                OrderSide.BUY, 100_000L, 1L, inverseDelivery(), inverseDelivery());

        assertThat(linearChange.realizedPnlDeltaUnits()).isEqualTo(120L);
        assertThat(inverseChange.next()).isEqualTo(new PositionState(2L, 5L, 66_667L, 0L));
    }

    @Test
    void optionContractsUseLinearPremiumPositionRules() {
        ContractSpec option = new ContractSpec(4L, ContractType.VANILLA_OPTION, "USDT", 1L,
                100_000_000L, 100_000_000L, 0L, 0L);

        var change = calculator.apply(new PositionState(2L, 4L, 120L, 0L),
                OrderSide.SELL, 150L, 1L, option, option);

        assertThat(change.next()).isEqualTo(new PositionState(1L, 4L, 120L, 30L));
        assertThat(change.realizedPnlDeltaUnits()).isEqualTo(30L);
    }

    @Test
    void closesDeliveryAtSettlementPrice() {
        var change = calculator.closeAtSettlement(new PositionState(10L, 4L, 100L, 0L),
                130L, linearDelivery());

        assertThat(change.next()).isEqualTo(new PositionState(0L, 4L, 0L, 300L));
        assertThat(change.realizedPnlDeltaUnits()).isEqualTo(300L);
    }

    @Test
    void settlementReleasesResidualLinearEntryValue() {
        var change = calculator.closeAtSettlement(new PositionState(3L, 4L, 100L, 302L, 0L),
                101L, linearDelivery());

        assertThat(change.next()).isEqualTo(new PositionState(0L, 4L, 0L, 0L, 1L));
        assertThat(change.realizedPnlDeltaUnits()).isEqualTo(1L);
    }

    @Test
    void closesWorthlessOptionAtZeroIntrinsicValue() {
        ContractSpec option = new ContractSpec(4L, ContractType.VANILLA_OPTION, "USDT", 1L,
                100_000_000L, 100_000_000L, 0L, 0L);

        var change = calculator.closeAtSettlement(new PositionState(2L, 4L, 120L, 0L),
                0L, option);

        assertThat(change.next()).isEqualTo(new PositionState(0L, 4L, 0L, -240L));
        assertThat(change.realizedPnlDeltaUnits()).isEqualTo(-240L);
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

    private ContractSpec linearDelivery() {
        return new ContractSpec(4L, ContractType.LINEAR_DELIVERY, "USDT", 1L,
                100_000_000L, 100_000_000L, 0L, 0L);
    }

    private ContractSpec inverseDelivery() {
        return new ContractSpec(5L, ContractType.INVERSE_DELIVERY, "BTC",
                10_000_000_000L, 100_000_000L, 100_000_000L, 0L, 0L);
    }
}
