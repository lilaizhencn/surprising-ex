package com.surprising.derivatives.lifecycle;

import com.surprising.adl.provider.service.AdlService;
import com.surprising.insurance.provider.service.InsuranceService;
import com.surprising.liquidation.provider.service.LiquidationService;
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

    public void processLiquidationWork() {
        liquidation.processWork();
    }

    public void coverInsuranceDeficits() {
        insurance.coverDeficits();
    }

    public void processAdlDeficits() {
        adl.processResidualDeficits();
    }
}
