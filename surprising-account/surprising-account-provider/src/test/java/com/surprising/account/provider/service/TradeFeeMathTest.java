package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.provider.model.ContractSpec;
import com.surprising.instrument.api.model.ContractType;
import org.junit.jupiter.api.Test;

class TradeFeeMathTest {

    @Test
    void positiveLinearFeeRateDebitsUser() {
        ContractSpec spec = new ContractSpec(1L, ContractType.LINEAR_PERPETUAL, "USDT",
                100L, 1L, 1L, 200L, 500L);

        assertThat(TradeFeeMath.feeDeltaUnits(spec, 100L, 6L, true)).isEqualTo(-30L);
        assertThat(TradeFeeMath.feeDeltaUnits(spec, 100L, 6L, false)).isEqualTo(-12L);
    }

    @Test
    void negativeFeeRateCreditsRebate() {
        ContractSpec spec = new ContractSpec(1L, ContractType.LINEAR_PERPETUAL, "USDT",
                100L, 1L, 1L, -100L, 500L);

        assertThat(TradeFeeMath.feeDeltaUnits(spec, 100L, 6L, false)).isEqualTo(6L);
    }

    @Test
    void inverseFeeConvertsNotionalToSettlementAssetUnits() {
        ContractSpec spec = new ContractSpec(2L, ContractType.INVERSE_PERPETUAL, "BTC",
                10_000_000_000L, 100_000_000L, 100_000_000L, 200L, 500L);

        assertThat(TradeFeeMath.feeDeltaUnits(spec, 50_000L, 1L, true)).isEqualTo(-100L);
    }
}
