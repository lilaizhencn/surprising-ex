package com.surprising.funding.provider.service;

import com.surprising.derivatives.lifecycle.LifecycleBusinessSettings;
import com.surprising.derivatives.lifecycle.LifecycleBusinessSettingsService;
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
        return Map.of("scope", "DATABASE", "version", saved.version(), "updatedBy", saved.updatedBy(), "reason", saved.reason(),
                "calculation", Map.of("enabled", value.calculationEnabled(), "publishDelayMs", value.publishDelayMs(), "maxMarkAgeMs", value.maxMarkAgeMs(), "maxRateAgeMs", value.maxRateAgeMs()),
                "settlement", Map.of("enabled", value.settlementEnabled(), "settleDelayMs", value.settleDelayMs(), "batchSize", value.batchSize(), "maxPagesPerRun", value.maxPagesPerRun()),
                "coordination", Map.of("enabled", value.coordinationEnabled(), "leaseDurationMs", value.leaseDurationMs()));
    }

    public Map<String, Object> update(String admin, Long expectedVersion, Boolean calculationEnabled,
            Boolean settlementEnabled, Boolean coordinationEnabled, Long publishDelayMs, Long settleDelayMs,
            Integer batchSize, Integer paymentPageSize, Integer maxPagesPerRun, Integer reconcileBatchSize,
            Long maxMarkAgeMs, Long maxRateAgeMs, Long leaseDurationMs, String reason) {
        if (paymentPageSize != null || reconcileBatchSize != null)
            throw new IllegalArgumentException("paymentPageSize and reconcileBatchSize are not runtime business controls");
        synchronized (settings) {
        var all = settings.current().settings();
        var old = all.funding();
        var next = new LifecycleBusinessSettings.Funding(value(calculationEnabled, old.calculationEnabled()),
                value(settlementEnabled, old.settlementEnabled()), value(coordinationEnabled, old.coordinationEnabled()),
                value(publishDelayMs, old.publishDelayMs()), value(maxMarkAgeMs, old.maxMarkAgeMs()), value(maxRateAgeMs, old.maxRateAgeMs()),
                value(settleDelayMs, old.settleDelayMs()), value(batchSize, old.batchSize()),
                value(maxPagesPerRun, old.maxPagesPerRun()), value(leaseDurationMs, old.leaseDurationMs()));
        settings.save(new LifecycleBusinessSettings(next, all.liquidation(), all.insurance(), all.adl()), expectedVersion, admin, reason);
        return current();
        }
    }
    private static <T> T value(T update, T previous) { return update == null ? previous : update; }
}
