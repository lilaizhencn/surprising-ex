package com.surprising.derivatives.lifecycle;

import com.surprising.adl.provider.service.AdlService;
import com.surprising.insurance.provider.service.InsuranceService;
import com.surprising.liquidation.provider.service.LiquidationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single scheduler boundary for the derivatives lifecycle domains. */
@Component
public final class DerivativesLifecycleMaintenanceTask {

    private final LiquidationService liquidation;
    private final InsuranceService insurance;
    private final AdlService adl;
    private final LifecycleBusinessSettingsService settings;
    private long lastLiquidationNanos;
    private long lastInsuranceNanos;
    private long lastAdlNanos;

    public DerivativesLifecycleMaintenanceTask(LiquidationService liquidation,
                                               InsuranceService insurance,
                                               AdlService adl, LifecycleBusinessSettingsService settings) {
        this.liquidation = liquidation;
        this.insurance = insurance;
        this.adl = adl;
        this.settings = settings;
    }

    @Scheduled(fixedDelay = 25)
    public void processLiquidationWork() {
        long now = System.nanoTime();
        if (lastLiquidationNanos != 0 && now - lastLiquidationNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(settings.current().settings().liquidation().delayMs())) return;
        lastLiquidationNanos = now;
        liquidation.processWork();
    }

    @Scheduled(fixedDelay = 25)
    public void coverInsuranceDeficits() {
        long now = System.nanoTime();
        if (lastInsuranceNanos != 0 && now - lastInsuranceNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(settings.current().settings().insurance().scanDelayMs())) return;
        lastInsuranceNanos = now;
        insurance.coverDeficits();
    }

    @Scheduled(fixedDelay = 25)
    public void processAdlDeficits() {
        long now = System.nanoTime();
        if (lastAdlNanos != 0 && now - lastAdlNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(settings.current().settings().adl().scanDelayMs())) return;
        lastAdlNanos = now;
        adl.processResidualDeficits();
    }
}
