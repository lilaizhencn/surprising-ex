package com.surprising.marketmaker.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SurprisingMarketMakerApplicationTest {

    @Test
    void logsTheEffectiveInternalMarkDrivenMakerMatrix(CapturedOutput output) {
        MarketMakerProperties properties = new MarketMakerProperties();
        properties.getEngine().setCycleDelayMs(50L);
        properties.getQuoting().setOrderLevels(50);
        properties.getQuoting().setMaxOrderOperationsPerCycle(160);
        MarketMakerProperties.Strategy strategy = new MarketMakerProperties.Strategy();
        strategy.setAccountIds(List.of(900001L, 900002L, 900003L, 900004L));
        properties.setStrategies(List.of(strategy));

        new SurprisingMarketMakerApplication(properties).logEffectiveMarketMatrixConfiguration();

        assertThat(output).contains(
                "cycleDelayMs=50",
                "orderLevels=50",
                "maxOperationsPerCycle=160",
                "accountCount=4",
                "referenceMarketEnabled=false",
                "marketTakingEnabled=false");
    }
}
