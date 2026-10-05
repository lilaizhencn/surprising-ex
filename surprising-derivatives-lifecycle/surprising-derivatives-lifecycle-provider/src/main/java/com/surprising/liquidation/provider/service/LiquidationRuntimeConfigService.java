package com.surprising.liquidation.provider.service;

import com.surprising.derivatives.lifecycle.LifecycleBusinessSettings;
import com.surprising.derivatives.lifecycle.LifecycleBusinessSettingsService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LiquidationRuntimeConfigService {
    private final LifecycleBusinessSettingsService settings;
    private final LiquidationAeronGateway aeron;
    public LiquidationRuntimeConfigService(LifecycleBusinessSettingsService settings, LiquidationAeronGateway aeron) {
        this.settings = settings; this.aeron = aeron;
    }
    public Map<String, Object> current() {
        var saved = settings.current();
        var value = saved.settings().liquidation();
        Map<String, Object> coordinator = new LinkedHashMap<>();
        coordinator.put("mode", "AERON_TAKEOVER");
        coordinator.put("delayMs", value.delayMs());
        coordinator.put("workBatchSize", value.workBatchSize());
        coordinator.put("maxPagesPerRun", value.maxPagesPerRun());
        coordinator.put("maxWorkBytes", value.maxWorkBytes());
        var scan = aeron.riskScanControl();
        coordinator.put("riskScanControlVersion", scan.version());
        coordinator.put("riskScanEnabled", scan.enabled());
        coordinator.put("riskScanDelayMs", scan.scanDelayMs());
        coordinator.put("riskScanBatchSize", scan.scanBatchSize());
        return Map.of("scope", "DATABASE", "version", saved.version(), "updatedBy", saved.updatedBy(), "reason", saved.reason(),
                "execution", Map.of("enabled", value.enabled(), "liquidationFeeRatePpm", value.feeRatePpm()), "coordinator", coordinator);
    }
    public Map<String, Object> update(String admin, Long expectedVersion, Boolean enabled, Long feeRatePpm,
            Long delayMs, Integer workBatchSize, Integer maxPagesPerRun, Integer maxWorkBytes, String reason) {
        synchronized (settings) {
        var all = settings.current().settings();
        var old = all.liquidation();
        var next = new LifecycleBusinessSettings.Liquidation(value(enabled, old.enabled()), value(feeRatePpm, old.feeRatePpm()),
                value(delayMs, old.delayMs()), value(workBatchSize, old.workBatchSize()), value(maxPagesPerRun, old.maxPagesPerRun()),
                value(maxWorkBytes, old.maxWorkBytes()));
        settings.save(new LifecycleBusinessSettings(all.funding(), next, all.insurance(), all.adl()), expectedVersion, admin, reason);
        return current();
        }
    }
    private static <T> T value(T update, T previous) { return update == null ? previous : update; }
}
