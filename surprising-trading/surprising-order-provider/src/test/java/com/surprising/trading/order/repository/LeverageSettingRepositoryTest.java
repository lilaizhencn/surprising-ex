package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.trading.api.model.MarginMode;
import org.junit.jupiter.api.Test;

class LeverageSettingRepositoryTest {

    @Test
    void instrumentDefaultCapsLeverageAndRaisesInitialMarginRate() {
        LeverageSettingRepository repository = new LeverageSettingRepository(null);

        var response = repository.instrumentDefault(1001L, "btc-usdt", MarginMode.CROSS,
                100_000_000L, 5_000L);

        assertThat(response.symbol()).isEqualTo("BTC-USDT");
        assertThat(response.leveragePpm()).isEqualTo(100_000_000L);
        assertThat(response.initialMarginRatePpm()).isEqualTo(10_000L);
        assertThat(response.source()).isEqualTo("INSTRUMENT_DEFAULT");
    }
}
