package com.surprising.aeron.service.market;

import com.surprising.aeron.service.instrument.CoreInstrument;

/** 运行时的一版行情，使用整数币对编号并带上币对配置引用，供风险估值、成交准入及条件单扫描读取。 */
public record MarkPrice(int symbolId, CoreInstrument instrument, long markPriceTicks,
                               long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                               long generatedAtEpochMillis, long lastPriceTicks) {
    /** 创建尚未提供最新成交价的行情，lastPriceTicks 设为 0，其余行情和配置由调用方传入。 */
    public MarkPrice(int symbolId, CoreInstrument instrument, long markPriceTicks,
                            long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                            long generatedAtEpochMillis) {
        this(symbolId, instrument, markPriceTicks, indexPriceTicks, forwardPriceTicks,
                priceSequence, generatedAtEpochMillis, 0);
    }
    public MarkPrice {
        if (instrument != null && !instrument.contractType().isOption()) forwardPriceTicks = 0;
        if (symbolId < 0 || instrument == null || markPriceTicks <= 0
                || indexPriceTicks < 0 || forwardPriceTicks < 0 || lastPriceTicks < 0 || priceSequence <= 0
                || generatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid runtime mark price");
        }
    }

    /** 创建只提供标记价的行情，指数价、远期价和最新成交价都设为 0。 */
    public MarkPrice(int symbolId, CoreInstrument instrument, long markPriceTicks, long priceSequence,
                            long generatedAtEpochMillis) {
        this(symbolId, instrument, markPriceTicks, 0, 0, priceSequence, generatedAtEpochMillis);
    }
}
