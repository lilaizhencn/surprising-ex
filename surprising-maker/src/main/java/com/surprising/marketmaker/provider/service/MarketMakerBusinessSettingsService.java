package com.surprising.marketmaker.provider.service;

import com.surprising.marketmaker.provider.config.MarketMakerBusinessSettings;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.store.MarketMakerBusinessSettingsStore;
import org.springframework.stereotype.Service;

@Service
public class MarketMakerBusinessSettingsService {
    private final MarketMakerProperties properties;
    private final MarketMakerBusinessSettingsStore store;
    public MarketMakerBusinessSettingsService(MarketMakerProperties properties, MarketMakerBusinessSettingsStore store) {
        this.properties = properties; this.store = store;
    }
    public MarketMakerBusinessSettingsStore.Settings current() { return store.load(properties.getProductLine()); }
    public synchronized MarketMakerBusinessSettingsStore.Settings save(MarketMakerBusinessSettings settings, long version, String admin, String reason) {
        var candidate = new MarketMakerProperties();
        candidate.setProductLine(properties.getProductLine());
        candidate.install(settings);
        candidate.validateBusinessSettings();
        if (settings.referenceMarket().getMaxQuantitySteps() < settings.referenceMarket().getMinQuantitySteps()
                || settings.trade().getMaxQuantitySteps() < settings.trade().getMinQuantitySteps())
            throw new IllegalArgumentException("maximum quantity must not be below minimum quantity");
        var saved = store.save(properties.getProductLine(), settings, version, admin, reason);
        properties.install(saved.settings());
        return saved;
    }
}
