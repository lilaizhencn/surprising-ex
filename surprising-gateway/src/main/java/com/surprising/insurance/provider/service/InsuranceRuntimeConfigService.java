package com.surprising.insurance.provider.service;

import com.surprising.derivatives.lifecycle.model.LifecycleBusinessSettings;
import com.surprising.derivatives.lifecycle.model.LifecycleSettingsSnapshot;
import com.surprising.derivatives.lifecycle.service.LifecycleBusinessSettingsService;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class InsuranceRuntimeConfigService {
    private final LifecycleBusinessSettingsService settings;
    public InsuranceRuntimeConfigService(LifecycleBusinessSettingsService settings) { this.settings = settings; }
    public Map<String, Object> current() {
        var saved = settings.current();
        var value = saved.settings().insurance();
        return saved.responseEnvelope(Map.of("coverage", Map.of(
                "enabled", value.enabled(), "scanDelayMs", value.scanDelayMs(), "batchSize", value.batchSize())));
    }
    public Map<String, Object> update(String admin, long expectedVersion, Boolean enabled, Long scanDelayMs, Integer batchSize, String reason) {
        var all = settings.current().settings();
        var old = all.insurance();
        var next = new LifecycleBusinessSettings.Insurance(
                LifecycleSettingsSnapshot.coalesce(enabled, old.enabled()),
                LifecycleSettingsSnapshot.coalesce(scanDelayMs, old.scanDelayMs()),
                LifecycleSettingsSnapshot.coalesce(batchSize, old.batchSize()));
        settings.save(new LifecycleBusinessSettings(all.funding(), all.liquidation(), next, all.adl()), expectedVersion, admin, reason);
        return current();
    }
}
