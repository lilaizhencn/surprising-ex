package com.surprising.funding.provider.service;

import com.surprising.derivatives.lifecycle.model.LifecycleBusinessSettings;
import com.surprising.derivatives.lifecycle.model.LifecycleSettingsSnapshot;
import com.surprising.derivatives.lifecycle.service.LifecycleBusinessSettingsService;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 资金费设置的后台入口：校验完整候选值后按版本保存，不直接修改运行对象。 */
@Service
public class FundingRuntimeConfigService {
    private final LifecycleBusinessSettingsService settings;
    public FundingRuntimeConfigService(LifecycleBusinessSettingsService settings) { this.settings = settings; }

    public Map<String, Object> current() {
        var saved = settings.current();
        var value = saved.settings().funding();
        return saved.responseEnvelope(Map.of(
                "calculation", Map.of("enabled", value.calculationEnabled(), "publishDelayMs", value.publishDelayMs(), "maxMarkAgeMs", value.maxMarkAgeMs(), "maxRateAgeMs", value.maxRateAgeMs()),
                "settlement", Map.of("enabled", value.settlementEnabled(), "settleDelayMs", value.settleDelayMs(), "batchSize", value.batchSize(), "maxPagesPerRun", value.maxPagesPerRun()),
                "coordination", Map.of("enabled", value.coordinationEnabled(), "leaseDurationMs", value.leaseDurationMs())));
    }

    public Map<String, Object> update(String admin, long expectedVersion, Boolean calculationEnabled,
            Boolean settlementEnabled, Boolean coordinationEnabled, Long publishDelayMs, Long settleDelayMs,
            Integer batchSize, Integer maxPagesPerRun,
            Long maxMarkAgeMs, Long maxRateAgeMs, Long leaseDurationMs, String reason) {
        var all = settings.current().settings();
        var old = all.funding();
        var next = new LifecycleBusinessSettings.Funding(
                LifecycleSettingsSnapshot.coalesce(calculationEnabled, old.calculationEnabled()),
                LifecycleSettingsSnapshot.coalesce(settlementEnabled, old.settlementEnabled()),
                LifecycleSettingsSnapshot.coalesce(coordinationEnabled, old.coordinationEnabled()),
                LifecycleSettingsSnapshot.coalesce(publishDelayMs, old.publishDelayMs()),
                LifecycleSettingsSnapshot.coalesce(maxMarkAgeMs, old.maxMarkAgeMs()),
                LifecycleSettingsSnapshot.coalesce(maxRateAgeMs, old.maxRateAgeMs()),
                LifecycleSettingsSnapshot.coalesce(settleDelayMs, old.settleDelayMs()),
                LifecycleSettingsSnapshot.coalesce(batchSize, old.batchSize()),
                LifecycleSettingsSnapshot.coalesce(maxPagesPerRun, old.maxPagesPerRun()),
                LifecycleSettingsSnapshot.coalesce(leaseDurationMs, old.leaseDurationMs()));
        settings.save(new LifecycleBusinessSettings(next, all.liquidation(), all.insurance(), all.adl()), expectedVersion, admin, reason);
        return current();
    }
}
