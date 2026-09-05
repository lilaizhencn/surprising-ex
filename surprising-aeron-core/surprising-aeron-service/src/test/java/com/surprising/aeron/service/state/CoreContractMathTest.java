package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreContractMathTest {

    @Test
    void optionBuyerZeroMarginDoesNotEvaluateOverflowingPremium() {
        CoreInstrumentState instrument = CoreInstrumentState.from(ProductLine.OPTION,
                new UpsertInstrumentCommand("BTC-OPTION", 1, ContractType.VANILLA_OPTION.ordinal(),
                        "BTC", "USDT", "USDT", 2, 1, 1,
                        100_000, 50_000, 0, 0, 2_000_000_000_000L,
                        OptionType.CALL.ordinal(), 100));

        assertThat(CoreContractMath.openingMarginUnits(
                instrument, CoreOrderSide.BUY, Long.MAX_VALUE, 2, 100_000, 0, 0)).isZero();
        assertThat(CoreContractMath.openingMarginUnits(
                instrument, CoreOrderSide.BUY, Long.MAX_VALUE, 2, 0, 0)).isZero();
    }

    @Test
    void maintenanceMarginUsesTheInstrumentRiskBracketForCurrentNotional() {
        CoreInstrumentState instrument = CoreInstrumentState.from(ProductLine.LINEAR_PERPETUAL,
                new UpsertInstrumentCommand("BTC-USDT", 1,
                        com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 0, -1, 0,
                        10_000_000, 10_000, 0, 1,
                        List.of(
                                new CoreRiskLimitBracket(1, 0, 1_000, 10_000_000, 100_000, 200_000),
                                new CoreRiskLimitBracket(2, 1_000, 10_000, 5_000_000, 200_000, 500_000))));

        assertThat(CoreContractMath.maintenanceMarginUnits(instrument, 5, 100, 0, 0)).isEqualTo(100);
        assertThat(CoreContractMath.maintenanceMarginUnits(instrument, 20, 100, 0, 0)).isEqualTo(1_000);
        assertThat(CoreContractMath.openingMarginUnits(instrument, CoreOrderSide.BUY, 100, 20, 0, 0))
                .isEqualTo(400);
        assertThat(CoreContractMath.maintenanceMarginUnits(instrument, 200, 100, 0, 0)).isPositive();
        assertThat(CoreContractMath.riskBracket(instrument, 10_000).bracketNo()).isEqualTo(2);
        assertThatThrownBy(() -> CoreContractMath.riskBracket(instrument, 10_001))
                .isInstanceOf(CoreStateRejectedException.class)
                .hasMessageContaining("exceeds instrument risk brackets");
    }

    @Test
    void optionShortMarginUsesIndexForwardOtmAndCurrentPremium() {
        CoreInstrumentState instrument = CoreInstrumentState.from(ProductLine.OPTION,
                new UpsertInstrumentCommand("BTC-OPTION", 1, ContractType.VANILLA_OPTION.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 2_000_000_000_000L,
                        OptionType.CALL.ordinal(), 100, 10_000_000, 10_000, 0, 1,
                        List.of(new CoreRiskLimitBracket(1, 0, 10_000, 10_000_000,
                                200_000, 50_000, 1_200_000))));

        assertThat(CoreContractMath.openingMarginUnits(
                instrument, CoreOrderSide.SELL, 10, 2, 200_000, 100, 80,
                1_200_000)).isEqualTo(44);
        assertThat(CoreContractMath.optionSellOpenOrderMarginUnits(instrument,
                10, 10, 2, 100, 80, instrument.riskLimitBrackets().getFirst())).isEqualTo(24);
        assertThat(CoreContractMath.maintenanceMarginUnits(instrument, -2, 10, 100, 80)).isEqualTo(32);
        assertThat(CoreContractMath.optionMarketValueUnits(instrument, -2, 10)).isEqualTo(-20);
    }

    @Test
    void putShortMarginUsesPutOtmDirectionAndMarkBasedMaintenanceFloor() {
        CoreInstrumentState instrument = CoreInstrumentState.from(ProductLine.OPTION,
                new UpsertInstrumentCommand("BTC-PUT", 1, ContractType.VANILLA_OPTION.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 2_000_000_000_000L,
                        OptionType.PUT.ordinal(), 100, 10_000_000, 10_000, 0, 1,
                        List.of(new CoreRiskLimitBracket(1, 0, 10_000, 10_000_000,
                                200_000, 50_000, 1_200_000))));

        assertThat(CoreContractMath.openingMarginUnits(
                instrument, CoreOrderSide.SELL, 10, 2, 200_000, 100, 120,
                1_200_000)).isEqualTo(44);
        assertThat(CoreContractMath.maintenanceMarginUnits(instrument, -2, 200, 100, 120))
                .isEqualTo(424);
    }

    @Test
    void initialMarginRateUsesExactAllocationFreeCeilingDivision() {
        assertThat(CoreContractMath.initialMarginRateFromLeverage(20_000_000)).isEqualTo(50_000);
        assertThat(CoreContractMath.initialMarginRateFromLeverage(3_000_000)).isEqualTo(333_334);
        assertThat(CoreContractMath.initialMarginRateFromLeverage(Long.MAX_VALUE)).isEqualTo(1);
    }

    @Test
    void openInterestScalingUsesLongFastPathAndPreservesOverflowSemantics() {
        assertThat(CoreContractMath.scaledFloorCapped(
                20_000, 250_000, 1_000_000, 1_000, 10_000)).isEqualTo(5_000);
        assertThat(CoreContractMath.scaledFloorCapped(
                1, 1, 1_000_000, 1_000, 10_000)).isEqualTo(1_000);
        assertThat(CoreContractMath.scaledFloorCapped(
                Long.MAX_VALUE, 1_000_000, 1_000_000, 0, 5_000)).isEqualTo(5_000);
        assertThat(CoreContractMath.scaledFloorCapped(
                1, 1, 1, 10_000, 5_000)).isEqualTo(5_000);
    }
}
