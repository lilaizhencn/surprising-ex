package com.surprising.aeron.service.state;

/**
 * Core-owned status policy. Margin rates and risk limits come from the versioned instrument;
 * this policy only maps the resulting margin ratio to an operational status.
 */
final class CoreRiskPolicy {

    static final int VERSION = 1;
    static final long WARNING_MARGIN_RATIO_PPM = 800_000L;
    static final long LIQUIDATION_MARGIN_RATIO_PPM = 1_000_000L;

    private CoreRiskPolicy() {
    }

    static CoreRiskStatus status(long ratioPpm) {
        return ratioPpm >= LIQUIDATION_MARGIN_RATIO_PPM ? CoreRiskStatus.LIQUIDATION
                : ratioPpm >= WARNING_MARGIN_RATIO_PPM ? CoreRiskStatus.WARNING : CoreRiskStatus.NORMAL;
    }
}
