package com.surprising.marketmaker.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SurprisingMarketMakerApplicationTest {

    @Test
    void logsTheEffectiveInternalMarkDrivenMakerMatrix(CapturedOutput output) {
        MarketMakerProperties properties = new MarketMakerProperties();
        properties.setProductLine(com.surprising.product.api.ProductLine.LINEAR_PERPETUAL);

        new SurprisingMarketMakerApplication(properties).logEffectiveMarketMatrixConfiguration();

        assertThat(output).contains("YAML startup configuration", "productLine=LINEAR_PERPETUAL");
    }
}
