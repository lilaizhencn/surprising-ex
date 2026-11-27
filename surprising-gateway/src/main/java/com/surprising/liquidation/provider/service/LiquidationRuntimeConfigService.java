package com.surprising.liquidation.provider.service;

import com.surprising.derivatives.lifecycle.model.LifecycleBusinessSettings;
import com.surprising.derivatives.lifecycle.model.LifecycleSettingsSnapshot;
import com.surprising.derivatives.lifecycle.service.LifecycleBusinessSettingsService;
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
        return saved.responseEnvelope(Map.of(
                "execution", Map.of("enabled", value.enabled(), "liquidationFeeRatePpm", value.feeRatePpm()),
                "coordinator", coordinator));
    }
    public Map<String, Object> update(String admin, long expectedVersion, Boolean enabled, Long feeRatePpm,
            Long delayMs, Integer workBatchSize, Integer maxPagesPerRun, Integer maxWorkBytes, String reason) {
        var all = settings.current().settings();
        var old = all.liquidation();
        var next = new LifecycleBusinessSettings.Liquidation(
                LifecycleSettingsSnapshot.coalesce(enabled, old.enabled()),
                LifecycleSettingsSnapshot.coalesce(feeRatePpm, old.feeRatePpm()),
                LifecycleSettingsSnapshot.coalesce(delayMs, old.delayMs()),
                LifecycleSettingsSnapshot.coalesce(workBatchSize, old.workBatchSize()),
                LifecycleSettingsSnapshot.coalesce(maxPagesPerRun, old.maxPagesPerRun()),
                LifecycleSettingsSnapshot.coalesce(maxWorkBytes, old.maxWorkBytes()));
        settings.save(new LifecycleBusinessSettings(all.funding(), next, all.insurance(), all.adl()), expectedVersion, admin, reason);
        return current();
    }
}
