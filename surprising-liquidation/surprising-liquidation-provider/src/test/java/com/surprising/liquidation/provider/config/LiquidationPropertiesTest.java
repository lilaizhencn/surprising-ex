package com.surprising.liquidation.provider.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class LiquidationPropertiesTest {

    @Test
    void rejectsUnsafeCoordinatorAndAeronBounds() {
        LiquidationProperties properties = new LiquidationProperties();

        assertThatThrownBy(() -> properties.getAeron().setHostnames(List.of("one", "two")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.getCoordinator().setWorkBatchSize(1_001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.getExecution().setLiquidationFeeRatePpm(1_000_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
