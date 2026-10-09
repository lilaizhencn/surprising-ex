package com.surprising.marketmaker.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MarketMakerConfiguration {
    @Bean
    @ConfigurationProperties(prefix = "surprising.market-maker")
    MarketMakerProperties marketMakerProperties(MarketMakerInfrastructureProperties infrastructure) {
        var properties = new MarketMakerProperties();
        properties.setProductLine(infrastructure.getProductLine());
        properties.setKafka(infrastructure.getKafka());
        properties.getEngine().setNodeId(infrastructure.getNodeId());
        return properties;
    }
}
