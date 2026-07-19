package com.surprising.risk.provider.model;

import com.surprising.instrument.api.model.ContractType;
import java.util.Comparator;
import java.util.List;

/** Static calculation parameters cached in-process by instrument version. */
public record RiskInstrumentSpec(
        String symbol,
        long version,
        ContractType contractType,
        String settleAsset,
        long notionalMultiplierUnits,
        long priceTickUnits,
        long settleScaleUnits,
        long baseMaintenanceMarginRatePpm,
        List<RiskMaintenanceBracket> brackets) {

    public RiskInstrumentSpec {
        if (symbol == null || symbol.isBlank() || version <= 0L || contractType == null
                || settleAsset == null || settleAsset.isBlank() || notionalMultiplierUnits <= 0L
                || priceTickUnits <= 0L || settleScaleUnits <= 0L || baseMaintenanceMarginRatePpm <= 0L) {
            throw new IllegalArgumentException("invalid risk instrument spec");
        }
        brackets = brackets == null ? List.of() : brackets.stream()
                .sorted(Comparator.comparingLong(RiskMaintenanceBracket::notionalFloorUnits))
                .toList();
    }

    public long maintenanceMarginRatePpm(long notionalUnits) {
        long rate = baseMaintenanceMarginRatePpm;
        for (RiskMaintenanceBracket bracket : brackets) {
            if (bracket.notionalFloorUnits() > notionalUnits) {
                break;
            }
            rate = bracket.maintenanceMarginRatePpm();
        }
        return rate;
    }
}
