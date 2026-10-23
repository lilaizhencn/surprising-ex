package com.surprising.aeron.protocol;

/** 注册或更新标的；现货不携带衍生品风险配置，具体产品归属来自命令头。 */
public record RegisterInstrumentCommand(
        String instrumentId,
        String baseAsset,
        String quoteAsset,
        String settleAsset,
        long notionalMultiplierUnits,
        long priceTickUnits,
        long settleScaleUnits,
        CoreInstrumentRiskLimits riskLimits,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        long expiryEpochMillis,
        int optionTypeCode,
        long strikePriceTicks,
        int instrumentStatusCode,
        boolean marketOrderEnabled,
        boolean postOnlyEnabled,
        boolean reduceOnlyEnabled,
        int supportedOrderTypeMask,
        int supportedTimeInForceMask,
        long quantityStepUnits,
        CoreOrderProtection orderProtection) {

    public RegisterInstrumentCommand {
        com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
        if (baseAsset == null || baseAsset.isBlank() || quoteAsset == null || quoteAsset.isBlank()
                || settleAsset == null || settleAsset.isBlank() || notionalMultiplierUnits <= 0
                || Math.absExact(makerFeeRatePpm) > 1_000_000 || Math.absExact(takerFeeRatePpm) > 1_000_000
                || instrumentStatusCode < 0 || instrumentStatusCode > 5
                || supportedOrderTypeMask <= 0 || (supportedOrderTypeMask & ~0b11) != 0
                || supportedTimeInForceMask <= 0 || (supportedTimeInForceMask & ~0b1111) != 0
                || riskLimits != null && riskLimits.initialMarginRatePpm() > 1_000_000) {
            throw new IllegalArgumentException("invalid instrument command");
        }
    }

    public RegisterInstrumentCommand(
            String instrumentId, String baseAsset, String quoteAsset, String settleAsset, long notionalMultiplierUnits,
            long priceTickUnits, long settleScaleUnits, CoreInstrumentRiskLimits riskLimits, long makerFeeRatePpm,
            long takerFeeRatePpm, long expiryEpochMillis, int optionTypeCode, long strikePriceTicks,
            int instrumentStatusCode, boolean marketOrderEnabled, boolean postOnlyEnabled, boolean reduceOnlyEnabled,
            int supportedOrderTypeMask, int supportedTimeInForceMask, long quantityStepUnits) {
        this(
                instrumentId, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits, priceTickUnits,
                settleScaleUnits, riskLimits, makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionTypeCode,
                strikePriceTicks, instrumentStatusCode, marketOrderEnabled, postOnlyEnabled, reduceOnlyEnabled,
                supportedOrderTypeMask, supportedTimeInForceMask, quantityStepUnits,
                riskLimits == null ? null : CoreOrderProtection.initial());
    }

    public RegisterInstrumentCommand(
            String instrumentId, String baseAsset, String quoteAsset, String settleAsset, long notionalMultiplierUnits,
            long priceTickUnits, long settleScaleUnits, CoreInstrumentRiskLimits riskLimits, long makerFeeRatePpm,
            long takerFeeRatePpm, long expiryEpochMillis, int optionTypeCode, long strikePriceTicks,
            int instrumentStatusCode, boolean marketOrderEnabled, boolean postOnlyEnabled, boolean reduceOnlyEnabled,
            int supportedOrderTypeMask, int supportedTimeInForceMask) {
        this(
                instrumentId, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits, priceTickUnits,
                settleScaleUnits, riskLimits, makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionTypeCode,
                strikePriceTicks, instrumentStatusCode, marketOrderEnabled, postOnlyEnabled, reduceOnlyEnabled,
                supportedOrderTypeMask, supportedTimeInForceMask, 1L);
    }

    /** 交易工具使用全能力、单位数量步长的初始标的，风险配置仍由调用方明确提供。 */
    public RegisterInstrumentCommand(
            String instrumentId, String baseAsset, String quoteAsset, String settleAsset, long notionalMultiplierUnits,
            long priceTickUnits, long settleScaleUnits, CoreInstrumentRiskLimits riskLimits, long makerFeeRatePpm,
            long takerFeeRatePpm, long expiryEpochMillis, int optionTypeCode, long strikePriceTicks) {
        this(
                instrumentId, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits, priceTickUnits,
                settleScaleUnits, riskLimits, makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionTypeCode,
                strikePriceTicks, 1, true, true, true, 0b11, 0b1111);
    }
}
