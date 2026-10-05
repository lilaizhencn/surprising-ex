package com.surprising.insurance.provider.service;

import com.surprising.derivatives.lifecycle.LifecycleBusinessSettings;
import com.surprising.derivatives.lifecycle.LifecycleBusinessSettingsService;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class InsuranceRuntimeConfigService {
    private final LifecycleBusinessSettingsService settings;
    public InsuranceRuntimeConfigService(LifecycleBusinessSettingsService settings) { this.settings = settings; }
    public Map<String, Object> current() {
        var saved = settings.current();
        var value = saved.settings().insurance();
        return Map.of("scope", "DATABASE", "version", saved.version(), "updatedBy", saved.updatedBy(), "reason", saved.reason(),
                "coverage", Map.of("enabled", value.enabled(), "scanDelayMs", value.scanDelayMs(), "batchSize", value.batchSize()));
    }
    public Map<String, Object> update(String admin, Long expectedVersion, Boolean enabled, Long scanDelayMs, Integer batchSize, String reason) {
        synchronized (settings) {
        var all = settings.current().settings();
        var old = all.insurance();
        var next = new LifecycleBusinessSettings.Insurance(enabled == null ? old.enabled() : enabled,
                scanDelayMs == null ? old.scanDelayMs() : scanDelayMs, batchSize == null ? old.batchSize() : batchSize);
        settings.save(new LifecycleBusinessSettings(all.funding(), all.liquidation(), next, all.adl()), expectedVersion, admin, reason);
        return current();
        }
    }
}
