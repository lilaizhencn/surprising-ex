package com.surprising.aeron.protocol;

import java.util.List;
import com.surprising.product.api.ProductLine;

/** 衍生品的保证金、杠杆和持仓限额。现货不创建或保存此配置。 */
public record CoreInstrumentRiskLimits(
        long initialMarginRatePpm,
        long maxLeveragePpm,
        long maxPositionNotionalUnits,
        long userOpenInterestLimitRatePpm,
        long userOpenInterestLimitFloorUnits,
        List<CoreRiskLimitBracket> riskLimitBrackets) {
    public CoreInstrumentRiskLimits {
        if (initialMarginRatePpm <= 0
                || maxPositionNotionalUnits <= 0 || userOpenInterestLimitRatePpm < 0
                || userOpenInterestLimitFloorUnits <= 0 || riskLimitBrackets == null || riskLimitBrackets.isEmpty()) {
            throw new IllegalArgumentException("invalid instrument risk limits");
        }
        riskLimitBrackets = List.copyOf(riskLimitBrackets);
        long previousCap = 0;
        for (CoreRiskLimitBracket bracket : riskLimitBrackets) {
            if (bracket.notionalCapUnits() <= previousCap) {
                throw new IllegalArgumentException("risk limit bracket caps must increase");
            }
            previousCap = bracket.notionalCapUnits();
        }
        if (previousCap < maxPositionNotionalUnits) {
            throw new IllegalArgumentException("risk limit brackets must cover max position notional");
        }
    }

    /** 按真实产品校验金融参数，并移除该产品不使用的杠杆或期权因子。 */
    public CoreInstrumentRiskLimits forProduct(ProductLine productLine) {
        java.util.Objects.requireNonNull(productLine, "productLine");
        if (!productLine.isDerivative()) throw new IllegalArgumentException("spot has no derivative risk limits");
        boolean option = productLine.isOptionProduct();
        if (!option && maxLeveragePpm < 1_000_000L)
            throw new IllegalArgumentException("futures leverage must be at least 1x");
        boolean canonical = !option || maxLeveragePpm == 0;
        for (CoreRiskLimitBracket bracket : riskLimitBrackets) {
            if (option) {
                if (bracket.optionMarginFactorPpm() <= 0 || bracket.optionMarginFactorPpm() > 10_000_000L)
                    throw new IllegalArgumentException("invalid option margin factor");
                canonical &= bracket.maxLeveragePpm() == 0;
            } else {
                if (bracket.maxLeveragePpm() < 1_000_000L || bracket.maxLeveragePpm() > maxLeveragePpm)
                    throw new IllegalArgumentException("risk bracket leverage exceeds futures limits");
                canonical &= bracket.optionMarginFactorPpm() == 0;
            }
        }
        if (canonical) return this;
        return new CoreInstrumentRiskLimits(initialMarginRatePpm, option ? 0 : maxLeveragePpm,
                maxPositionNotionalUnits, userOpenInterestLimitRatePpm, userOpenInterestLimitFloorUnits,
                riskLimitBrackets.stream().map(bracket -> new CoreRiskLimitBracket(bracket.notionalCapUnits(),
                        option ? 0 : bracket.maxLeveragePpm(), bracket.initialMarginRatePpm(),
                        bracket.maintenanceMarginRatePpm(), option ? bracket.optionMarginFactorPpm() : 0)).toList());
    }
}
