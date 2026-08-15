package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreContractMathTest {

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

        assertThat(CoreContractMath.maintenanceMarginUnits(instrument, 5, 100)).isEqualTo(100);
        assertThat(CoreContractMath.maintenanceMarginUnits(instrument, 20, 100)).isEqualTo(1_000);
        assertThat(CoreContractMath.openingMarginUnits(instrument, CoreOrderSide.BUY, 100, 20))
                .isEqualTo(400);
        assertThat(CoreContractMath.riskBracket(instrument, 10_000).bracketNo()).isEqualTo(2);
        assertThatThrownBy(() -> CoreContractMath.riskBracket(instrument, 10_001))
                .isInstanceOf(CoreStateRejectedException.class)
                .hasMessageContaining("exceeds instrument risk brackets");
    }
}
