package com.surprising.marketmaker.provider.config;

import com.surprising.marketmaker.provider.repository.MarketMakerBusinessSettingsStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MarketMakerConfiguration {
    @Bean
    MarketMakerProperties marketMakerProperties(MarketMakerInfrastructureProperties infrastructure,
                                                MarketMakerBusinessSettingsStore store) {
        store.initializeDisabled(infrastructure.getProductLine());
        var properties = new MarketMakerProperties();
        properties.setProductLine(infrastructure.getProductLine());
        properties.setKafka(infrastructure.getKafka());
        properties.getEngine().setNodeId(infrastructure.getNodeId());
        properties.install(store.load(infrastructure.getProductLine()).settings());
        return properties;
    }
}
