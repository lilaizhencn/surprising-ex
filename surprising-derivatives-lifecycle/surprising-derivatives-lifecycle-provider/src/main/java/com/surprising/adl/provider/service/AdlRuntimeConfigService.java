package com.surprising.adl.provider.service;

import com.surprising.derivatives.lifecycle.LifecycleBusinessSettings;
import com.surprising.derivatives.lifecycle.LifecycleBusinessSettingsService;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AdlRuntimeConfigService {
    private final LifecycleBusinessSettingsService settings;
    public AdlRuntimeConfigService(LifecycleBusinessSettingsService settings) { this.settings = settings; }
    public Map<String, Object> current() {
        var saved = settings.current();
        var value = saved.settings().adl();
        return Map.of("scope", "DATABASE", "version", saved.version(), "updatedBy", saved.updatedBy(), "reason", saved.reason(),
                "scanner", Map.of("enabled", value.enabled(), "scanDelayMs", value.scanDelayMs(), "batchSize", value.batchSize(),
                        "maxDeleveragesPerDeficit", value.maxDeleveragesPerDeficit(), "candidateMultiplier", value.candidateMultiplier()));
    }
    public Map<String, Object> update(String admin, Long expectedVersion, Boolean enabled, Long scanDelayMs,
            Long minDeficitAgeMs, Long maxMarkAgeMs, Integer batchSize, Integer maxDeleveragesPerDeficit,
            Integer candidateMultiplier, String reason) {
        if (minDeficitAgeMs != null || maxMarkAgeMs != null)
            throw new IllegalArgumentException("ADL eligibility and mark freshness are owned by Core, not provider scanner settings");
        synchronized (settings) {
        var all = settings.current().settings();
        var old = all.adl();
        var next = new LifecycleBusinessSettings.Adl(value(enabled, old.enabled()), value(scanDelayMs, old.scanDelayMs()),
                value(batchSize, old.batchSize()), value(maxDeleveragesPerDeficit, old.maxDeleveragesPerDeficit()),
                value(candidateMultiplier, old.candidateMultiplier()));
        settings.save(new LifecycleBusinessSettings(all.funding(), all.liquidation(), all.insurance(), next), expectedVersion, admin, reason);
        return current();
        }
    }
    private static <T> T value(T update, T previous) { return update == null ? previous : update; }
}
