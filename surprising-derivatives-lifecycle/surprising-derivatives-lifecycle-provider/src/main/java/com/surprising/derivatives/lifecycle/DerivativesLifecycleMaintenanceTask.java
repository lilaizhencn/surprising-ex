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

    public DerivativesLifecycleMaintenanceTask(LiquidationService liquidation,
                                               InsuranceService insurance,
                                               AdlService adl) {
        this.liquidation = liquidation;
        this.insurance = insurance;
        this.adl = adl;
    }

    @Scheduled(fixedDelayString = "${surprising.liquidation.coordinator.delay-ms:25}")
    public void processLiquidationWork() {
        liquidation.processWork();
    }

    @Scheduled(fixedDelayString = "${surprising.insurance.coverage.scan-delay-ms:1000}")
    public void coverInsuranceDeficits() {
        insurance.coverDeficits();
    }

    @Scheduled(fixedDelayString = "${surprising.adl.scanner.scan-delay-ms:1000}")
    public void processAdlDeficits() {
        adl.processResidualDeficits();
    }
}
