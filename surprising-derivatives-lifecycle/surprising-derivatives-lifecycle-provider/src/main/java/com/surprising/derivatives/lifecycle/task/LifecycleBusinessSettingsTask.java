package com.surprising.derivatives.lifecycle.task;

import com.surprising.derivatives.lifecycle.service.LifecycleBusinessSettingsService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LifecycleBusinessSettingsTask {
    private final LifecycleBusinessSettingsService settings;
    public LifecycleBusinessSettingsTask(LifecycleBusinessSettingsService settings) { this.settings = settings; }
    @Scheduled(fixedDelay = 1000)
    public void reload() { settings.reload(); }
}
