package com.surprising.marketmaker.provider.config;

import com.surprising.product.api.ProductLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "surprising.market-maker.infrastructure", ignoreUnknownFields = false)
public class MarketMakerInfrastructureProperties {
    @NotNull private ProductLine productLine;
    private String nodeId;
    @Valid private MarketMakerProperties.Kafka kafka = new MarketMakerProperties.Kafka();
}
