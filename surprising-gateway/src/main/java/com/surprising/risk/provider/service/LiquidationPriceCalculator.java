package com.surprising.risk.provider.service;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.RiskLimitBracket;
import com.surprising.product.api.ProductLine;
import java.util.List;

/** Estimates the mark-price boundary at which Core's maintenance test becomes unsafe. */
final class LiquidationPriceCalculator {
    private final InstrumentSnapshotCache instruments;
    private final ProductLine productLine;

    LiquidationPriceCalculator(InstrumentSnapshotCache instruments, ProductLine productLine) {
        this.instruments = instruments;
        this.productLine = productLine;
    }

    Long price(CoreRiskSnapshotView position, List<CoreRiskSnapshotView> accountPositions) {
        if (position.signedQuantitySteps() == 0 || position.markPriceTicks() <= 0) return null;
        int id = Integer.parseInt(position.instrumentId());
        InstrumentResponse instrument = instruments.current(productLine, id).orElse(null);
        Long scale = instruments.scale(productLine, position.settleAsset()).orElse(null);
        if (instrument == null || scale == null || scale <= 0 || instrument.contractType().isOption()
                || instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT) return null;
        long otherMaintenance = 0;
        if (position.marginMode() == CoreMarginMode.CROSS) {
            for (CoreRiskSnapshotView other : accountPositions) {
                if (other == position || other.marginMode() != CoreMarginMode.CROSS
                        || !other.settleAsset().equals(position.settleAsset())) continue;
                otherMaintenance = Math.addExact(otherMaintenance, other.maintenanceMarginUnits());
            }
        }
        long fixedEquity = position.marginMode() == CoreMarginMode.CROSS
                ? Math.subtractExact(position.equityUnits(), position.unrealizedPnlUnits())
                : position.positionMarginUnits();
        long mark = position.markPriceTicks();
        if (unsafe(position, instrument, scale, fixedEquity, otherMaintenance, mark)) return mark;
        if (position.signedQuantitySteps() > 0) {
            if (!unsafe(position, instrument, scale, fixedEquity, otherMaintenance, 1)) return null;
            long low = 1, high = mark;
            while (low + 1 < high) {
                long mid = low + (high - low) / 2;
                if (unsafe(position, instrument, scale, fixedEquity, otherMaintenance, mid)) low = mid;
                else high = mid;
            }
            return low;
        }
        long low = mark, high = mark;
        while (high < Long.MAX_VALUE / 2) {
            high *= 2;
            if (unsafe(position, instrument, scale, fixedEquity, otherMaintenance, high)) break;
            low = high;
        }
        if (high == low || !unsafe(position, instrument, scale, fixedEquity, otherMaintenance, high)) return null;
        while (low + 1 < high) {
            long mid = low + (high - low) / 2;
            if (unsafe(position, instrument, scale, fixedEquity, otherMaintenance, mid)) high = mid;
            else low = mid;
        }
        return high;
    }

    private static boolean unsafe(CoreRiskSnapshotView position, InstrumentResponse instrument, long scale,
                                  long fixedEquity, long otherMaintenance, long price) {
        long quantity = position.signedQuantitySteps();
        long pnl = PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(), quantity,
                position.entryPriceTicks(), price, instrument.notionalMultiplierUnits(),
                instrument.priceTickUnits(), scale);
        long notional = PerpetualContractMath.notionalUnits(instrument.contractType(), quantity,
                price, instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), scale);
        RiskLimitBracket bracket = null;
        List<RiskLimitBracket> brackets = instrument.riskLimitBrackets() == null
                ? List.of() : instrument.riskLimitBrackets();
        for (RiskLimitBracket candidate : brackets) {
            if (candidate.notionalFloorUnits() <= notional && (bracket == null
                    || candidate.notionalFloorUnits() > bracket.notionalFloorUnits())) bracket = candidate;
        }
        if (bracket == null && !brackets.isEmpty()) return true;
        long rate = bracket == null ? instrument.maintenanceMarginRatePpm() : bracket.maintenanceMarginRatePpm();
        long maintenance = PerpetualContractMath.maintenanceMarginUnits(instrument.contractType(), quantity,
                price, instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), scale, rate);
        return Math.addExact(fixedEquity, pnl) <= Math.addExact(otherMaintenance, maintenance);
    }
}
