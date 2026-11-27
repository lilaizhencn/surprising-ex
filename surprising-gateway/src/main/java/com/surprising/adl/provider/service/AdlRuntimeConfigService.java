package com.surprising.adl.provider.service;

import com.surprising.derivatives.lifecycle.model.LifecycleBusinessSettings;
import com.surprising.derivatives.lifecycle.model.LifecycleSettingsSnapshot;
import com.surprising.derivatives.lifecycle.service.LifecycleBusinessSettingsService;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AdlRuntimeConfigService {
    private final LifecycleBusinessSettingsService settings;
    public AdlRuntimeConfigService(LifecycleBusinessSettingsService settings) { this.settings = settings; }
    public Map<String, Object> current() {
        var saved = settings.current();
        var value = saved.settings().adl();
        return saved.responseEnvelope(Map.of("scanner", Map.of(
                "enabled", value.enabled(), "scanDelayMs", value.scanDelayMs(), "batchSize", value.batchSize(),
                "maxDeleveragesPerDeficit", value.maxDeleveragesPerDeficit(), "candidateMultiplier", value.candidateMultiplier())));
    }
    public Map<String, Object> update(String admin, long expectedVersion, Boolean enabled, Long scanDelayMs,
            Integer batchSize, Integer maxDeleveragesPerDeficit,
            Integer candidateMultiplier, String reason) {
        var all = settings.current().settings();
        var old = all.adl();
        var next = new LifecycleBusinessSettings.Adl(
                LifecycleSettingsSnapshot.coalesce(enabled, old.enabled()),
                LifecycleSettingsSnapshot.coalesce(scanDelayMs, old.scanDelayMs()),
                LifecycleSettingsSnapshot.coalesce(batchSize, old.batchSize()),
                LifecycleSettingsSnapshot.coalesce(maxDeleveragesPerDeficit, old.maxDeleveragesPerDeficit()),
                LifecycleSettingsSnapshot.coalesce(candidateMultiplier, old.candidateMultiplier()));
        settings.save(new LifecycleBusinessSettings(all.funding(), all.liquidation(), all.insurance(), next), expectedVersion, admin, reason);
        return current();
    }
}
