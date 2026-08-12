package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.LiquidationSizingDecision;
import com.surprising.liquidation.provider.model.LiquidationSizingInput;
import org.springframework.stereotype.Component;

@Component
public class LiquidationSizingPolicy {

    private static final long PPM = 1_000_000L;

    public LiquidationSizingDecision decide(LiquidationSizingInput input,
                                            long marginRatioPpm,
                                            LiquidationProperties.Sizing sizing) {
        if (input.availableCloseSteps() <= 0) {
            return new LiquidationSizingDecision(0L, "NO_REDUCIBLE_QUANTITY");
        }
        if (marginRatioPpm >= Math.max(1L, sizing.getFullCloseMarginRatioPpm())) {
            return new LiquidationSizingDecision(input.availableCloseSteps(), "FULL_LIQUIDATION");
        }
        long tierReduction = tierReductionSteps(input);
        if (tierReduction > 0) {
            return new LiquidationSizingDecision(clampCloseSteps(tierReduction, input.availableCloseSteps(),
                    sizing.getMinCloseSteps()), "TIER_REDUCTION");
        }
        long ratioPpm = marginRatioPpm >= sizing.getSevereMarginRatioPpm()
                ? sizing.getSevereCloseRatioPpm()
                : sizing.getNormalCloseRatioPpm();
        long ratioSteps = ceilMultiplyDivide(input.availableCloseSteps(), Math.max(1L, ratioPpm), PPM);
        return new LiquidationSizingDecision(clampCloseSteps(ratioSteps, input.availableCloseSteps(),
                sizing.getMinCloseSteps()), "PARTIAL_LIQUIDATION");
    }

    private long tierReductionSteps(LiquidationSizingInput input) {
        if (input.bracketFloorNotionalUnits() <= 0 || input.notionalPerStepUnits() <= 0) {
            return 0L;
        }
        long targetNotional = input.bracketFloorNotionalUnits() - 1L;
        if (targetNotional <= 0) {
            return 0L;
        }
        long targetAbsSteps = Math.min(input.positionAbsSteps(), targetNotional / input.notionalPerStepUnits());
        long rawReduction = Math.max(0L, Math.subtractExact(input.positionAbsSteps(), targetAbsSteps));
        long pendingCloseSteps = Math.max(0L, Math.subtractExact(input.positionAbsSteps(), input.availableCloseSteps()));
        return Math.max(0L, Math.subtractExact(rawReduction, Math.min(rawReduction, pendingCloseSteps)));
    }

    private long clampCloseSteps(long requestedSteps, long availableCloseSteps, long minCloseSteps) {
        if (requestedSteps <= 0) {
            requestedSteps = Math.max(1L, minCloseSteps);
        }
        long min = Math.max(1L, minCloseSteps);
        long withMin = Math.max(min, requestedSteps);
        return Math.min(availableCloseSteps, withMin);
    }

    private long ceilMultiplyDivide(long left, long right, long divisor) {
        try {
            long product = Math.multiplyExact(left, right);
            long value = product / divisor;
            return product % divisor == 0 ? value : value + 1L;
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }
}
