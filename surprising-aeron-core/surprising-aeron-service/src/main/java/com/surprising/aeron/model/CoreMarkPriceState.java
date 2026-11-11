package com.surprising.aeron.model;

/** 某个币对一版不可变的行情记录，把标记价、指数价、远期价和最新成交价与行情序号一起保存，供风险判断和条件单扫描使用。 */
public record CoreMarkPriceState(String instrumentId, long markPriceTicks,
                                 long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                                 long generatedAtEpochMillis, long lastPriceTicks) {
    /** 创建包含标记价、指数价和远期价的行情记录，未提供的最新成交价记为 0。 */
    public CoreMarkPriceState(String instrumentId, long markPriceTicks,
                              long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                              long generatedAtEpochMillis) {
        this(instrumentId, markPriceTicks, indexPriceTicks, forwardPriceTicks,
                priceSequence, generatedAtEpochMillis, 0);
    }
    /** 校验币对编号及行情范围：标记价、行情序号和时间必须为正，其余价格允许用 0 表示未提供。 */
    public CoreMarkPriceState {
        instrumentId = InstrumentId.requireValid(instrumentId);
        if (markPriceTicks <= 0 || indexPriceTicks < 0 || forwardPriceTicks < 0 || lastPriceTicks < 0
                || priceSequence <= 0 || generatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid mark price state");
        }
    }

    /** 创建只带标记价的行情记录，指数价、远期价和最新成交价均记为未提供。 */
    public CoreMarkPriceState(String instrumentId, long markPriceTicks, long priceSequence,
                              long generatedAtEpochMillis) {
        this(instrumentId, markPriceTicks, 0, 0, priceSequence, generatedAtEpochMillis);
    }
}
