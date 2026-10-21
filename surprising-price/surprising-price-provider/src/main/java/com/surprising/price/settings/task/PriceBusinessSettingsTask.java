package com.surprising.price.settings.task;

import com.surprising.price.settings.service.PriceBusinessSettingsService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PriceBusinessSettingsTask {
    private final PriceBusinessSettingsService settings;
    public PriceBusinessSettingsTask(PriceBusinessSettingsService settings) { this.settings = settings; }
    @Scheduled(fixedDelay = 1000)
    public void reload() { settings.reload(); }
}
