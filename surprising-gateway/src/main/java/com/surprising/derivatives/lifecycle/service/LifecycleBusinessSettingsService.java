package com.surprising.derivatives.lifecycle.service;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.derivatives.lifecycle.model.LifecycleBusinessSettings;
import com.surprising.derivatives.lifecycle.model.LifecycleSettingsSnapshot;
import com.surprising.derivatives.lifecycle.repository.LifecycleBusinessSettingsRepository;
import com.surprising.product.api.ProductLine;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 保存完整生命周期规则，独立提交后安装，最后发布调度刷新事件。 */
@Service
public class LifecycleBusinessSettingsService {
    private final LifecycleBusinessSettingsRepository settings;
    private final ProductLine line;
    private final FundingProperties funding;
    private final LiquidationProperties liquidation;
    private final InsuranceProperties insurance;
    private final AdlProperties adl;
    private final ApplicationEventPublisher events;
    private volatile LifecycleSettingsSnapshot current;

    public LifecycleBusinessSettingsService(LifecycleBusinessSettingsRepository settings, RiskProperties risk,
            ObjectProvider<FundingProperties> funding, LiquidationProperties liquidation,
            InsuranceProperties insurance, AdlProperties adl, ApplicationEventPublisher events) {
        this.settings = settings; this.line = risk.getProductLine(); this.funding = funding.getIfAvailable();
        this.liquidation = liquidation; this.insurance = insurance; this.adl = adl; this.events = events;
    }
    @PostConstruct
    public void initialize() {
        settings.initialize(line, LifecycleBusinessSettings.initial());
        reload();
    }
    public LifecycleSettingsSnapshot current() {
        var value = current;
        if (value == null) throw new IllegalStateException("lifecycle business settings are not initialized");
        return value;
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized LifecycleSettingsSnapshot save(LifecycleBusinessSettings value, long expectedVersion, String admin, String reason) {
        if (current().version() != expectedVersion)
            throw new OptimisticLockingFailureException("lifecycle settings changed; reload before saving");
        var saved = settings.save(line, value, expectedVersion, admin, reason)
                .orElseThrow(() -> new OptimisticLockingFailureException("lifecycle settings changed; reload before saving"));
        install(saved);
        return current;
    }
    public synchronized void reload() {
        var value = settings.find(line);
        if (value == null) throw new IllegalStateException("lifecycle business settings are missing");
        if (current == null || value.version() > current.version()) install(value);
    }

    private void install(LifecycleSettingsSnapshot value) {
        var settings = value.settings();
        var calculation = new FundingProperties.Calculation();
        var settlement = new FundingProperties.Settlement();
        var coordination = new FundingProperties.Coordination();
        var fundingRules = settings.funding();
        calculation.setEnabled(fundingRules.calculationEnabled());
        calculation.setPublishDelayMs(fundingRules.publishDelayMs());
        calculation.setMaxMarkAge(Duration.ofMillis(fundingRules.maxMarkAgeMs()));
        calculation.setMaxRateAge(Duration.ofMillis(fundingRules.maxRateAgeMs()));
        settlement.setEnabled(fundingRules.settlementEnabled());
        settlement.setSettleDelayMs(fundingRules.settleDelayMs());
        settlement.setBatchSize(fundingRules.batchSize());
        settlement.setMaxPagesPerRun(fundingRules.maxPagesPerRun());
        coordination.setEnabled(fundingRules.coordinationEnabled());
        coordination.setLeaseDuration(Duration.ofMillis(fundingRules.leaseDurationMs()));
        if (funding != null) coordination.setNodeId(funding.getCoordination().getNodeId());
        var execution = new LiquidationProperties.Execution();
        var coordinator = new LiquidationProperties.Coordinator();
        var liquidationRules = settings.liquidation();
        execution.setEnabled(liquidationRules.enabled());
        execution.setLiquidationFeeRatePpm(liquidationRules.feeRatePpm());
        coordinator.setDelayMs(liquidationRules.delayMs());
        coordinator.setWorkBatchSize(liquidationRules.workBatchSize());
        coordinator.setMaxPagesPerRun(liquidationRules.maxPagesPerRun());
        coordinator.setMaxWorkBytes(liquidationRules.maxWorkBytes());
        var coverage = new InsuranceProperties.Coverage();
        coverage.setEnabled(settings.insurance().enabled());
        coverage.setScanDelayMs(settings.insurance().scanDelayMs());
        coverage.setBatchSize(settings.insurance().batchSize());
        var scanner = new AdlProperties.Scanner();
        var adlRules = settings.adl();
        scanner.setEnabled(adlRules.enabled());
        scanner.setScanDelayMs(adlRules.scanDelayMs());
        scanner.setBatchSize(adlRules.batchSize());
        scanner.setMaxDeleveragesPerDeficit(adlRules.maxDeleveragesPerDeficit());
        scanner.setCandidateMultiplier(adlRules.candidateMultiplier());
        if (funding != null) {
            funding.setCalculation(calculation);
            funding.setSettlement(settlement);
            funding.setCoordination(coordination);
        }
        liquidation.setExecution(execution);
        liquidation.setCoordinator(coordinator);
        insurance.setCoverage(coverage);
        adl.setScanner(scanner);
        current = value;
        events.publishEvent(value);
    }
}
