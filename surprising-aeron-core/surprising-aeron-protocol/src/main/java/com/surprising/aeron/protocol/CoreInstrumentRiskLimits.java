package com.surprising.aeron.protocol;

import java.util.List;

/** 衍生品的保证金、杠杆和持仓限额。现货不创建或保存此配置。 */
public record CoreInstrumentRiskLimits(
        long initialMarginRatePpm,
        long maxLeveragePpm,
        long maxPositionNotionalUnits,
        long userOpenInterestLimitRatePpm,
        long userOpenInterestLimitFloorUnits,
        List<CoreRiskLimitBracket> riskLimitBrackets) {
    public CoreInstrumentRiskLimits {
        if (initialMarginRatePpm <= 0 || maxLeveragePpm < 1_000_000L
                || maxPositionNotionalUnits <= 0 || userOpenInterestLimitRatePpm < 0
                || userOpenInterestLimitFloorUnits <= 0 || riskLimitBrackets == null || riskLimitBrackets.isEmpty()) {
            throw new IllegalArgumentException("invalid instrument risk limits");
        }
        riskLimitBrackets = List.copyOf(riskLimitBrackets);
        long previousCap = 0;
        for (CoreRiskLimitBracket bracket : riskLimitBrackets) {
            if (bracket.notionalCapUnits() <= previousCap
                    || bracket.maxLeveragePpm() > maxLeveragePpm) {
                throw new IllegalArgumentException("risk limit bracket caps must increase and leverage must be bounded");
            }
            previousCap = bracket.notionalCapUnits();
        }
        if (previousCap < maxPositionNotionalUnits) {
            throw new IllegalArgumentException("risk limit brackets must cover max position notional");
        }
    }
}
