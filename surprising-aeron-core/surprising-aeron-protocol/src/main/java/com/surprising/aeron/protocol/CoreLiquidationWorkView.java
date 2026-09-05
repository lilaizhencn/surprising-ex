package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.List;

public record CoreLiquidationWorkView(ProductLine productLine,
                                      long nextCursorLiquidationId,
                                      boolean complete,
                                      CoreRiskScanContinuation riskScanContinuation,
                                      List<CoreLiquidationActionView> actions,
                                      List<Resolution> resolutions) {
    public CoreLiquidationWorkView {
        if (productLine == null || nextCursorLiquidationId < 0 || actions == null || resolutions == null) {
            throw new IllegalArgumentException("invalid Core liquidation work");
        }
        actions = List.copyOf(actions);
        resolutions = List.copyOf(resolutions);
        if (!actions.isEmpty() && !resolutions.isEmpty()) {
            throw new IllegalArgumentException("Core work page must contain one purpose");
        }
    }

    public boolean riskScanPending() {
        return riskScanContinuation != null;
    }

    public enum Purpose {
        EXECUTION,
        INSURANCE,
        ADL
    }

    public record Query(ProductLine productLine, Purpose purpose, long afterLiquidationId,
                        int maxItems, int maxBytes) {
        public Query {
            if (productLine == null || purpose == null || afterLiquidationId < 0
                    || maxItems < 1 || maxItems > 1_000 || maxBytes < 256 || maxBytes > 1_048_576) {
                throw new IllegalArgumentException("invalid Core liquidation work query");
            }
        }
    }

    public record Resolution(long liquidationId, long userId, String symbol, String asset,
                             CoreMarginMode marginMode, CorePositionSide positionSide,
                             long instrumentVersion, long triggerPriceSequence,
                             long signedQuantitySteps, long deficitUnits, long recommendedCoveredUnits,
                             Purpose purpose) {
        public Resolution {
            if (liquidationId <= 0 || userId <= 0 || symbol == null || symbol.isBlank()
                    || asset == null || asset.isBlank() || marginMode == null || positionSide == null
                    || instrumentVersion <= 0 || triggerPriceSequence <= 0 || signedQuantitySteps == 0
                    || deficitUnits <= 0 || recommendedCoveredUnits < 0 || recommendedCoveredUnits > deficitUnits
                    || purpose == null || purpose == Purpose.EXECUTION
                    || purpose == Purpose.ADL && recommendedCoveredUnits != 0) {
                throw new IllegalArgumentException("invalid Core liquidation resolution work");
            }
        }
    }
}
