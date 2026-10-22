package com.surprising.aeron.model;

import com.surprising.aeron.service.order.state.OrderReservation;

/**
 * 某个币对一版不可变的行情记录，把标记价、指数价、远期价和最新成交价与行情序号一起保存，供风险判断和条件单扫描使用。
 * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
 * @param markPriceTicks 风险估值使用的标记价格，单位为价格 tick
 * @param indexPriceTicks 标的指数价格 tick，0 表示这份记录没有提供该价格
 * @param forwardPriceTicks 与期权到期日对应的远期价格 tick，0 表示没有提供
 * @param priceSequence 行情的单调序号，用来识别扫描和估值采用的是哪一版价格
 * @param generatedAtEpochMillis 行情来源生成这份价格的 Unix 毫秒时间戳
 * @param lastPriceTicks 最新成交价格 tick，0 表示这份行情没有提供最新成交价
 */
public record CoreMarkPriceState(String instrumentId, long markPriceTicks,
                                 long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                                 long generatedAtEpochMillis, long lastPriceTicks) {
    /**
     * 创建包含标记价、指数价和远期价的行情记录，未提供的最新成交价记为 0。
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param markPriceTicks 风险估值使用的标记价格，单位为价格 tick
     * @param indexPriceTicks 标的指数价格 tick，0 表示这份记录没有提供该价格
     * @param forwardPriceTicks 与期权到期日对应的远期价格 tick，0 表示没有提供
     * @param priceSequence 行情的单调序号，用来识别扫描和估值采用的是哪一版价格
     * @param generatedAtEpochMillis 行情来源生成这份价格的 Unix 毫秒时间戳
     */
    public CoreMarkPriceState(String instrumentId, long markPriceTicks,
                              long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                              long generatedAtEpochMillis) {
        this(instrumentId, markPriceTicks, indexPriceTicks, forwardPriceTicks,
                priceSequence, generatedAtEpochMillis, 0);
    }
    /**
     * 校验币对编号及行情范围：标记价、行情序号和时间必须为正，其余价格允许用 0 表示未提供。
     */
    public CoreMarkPriceState {
        instrumentId = OrderReservation.requireInstrumentId(instrumentId);
        if (markPriceTicks <= 0 || indexPriceTicks < 0 || forwardPriceTicks < 0 || lastPriceTicks < 0
                || priceSequence <= 0 || generatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid mark price state");
        }
    }

    /**
     * 创建只带标记价的行情记录，指数价、远期价和最新成交价均记为未提供。
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param markPriceTicks 风险估值使用的标记价格，单位为价格 tick
     * @param priceSequence 行情的单调序号，用来识别扫描和估值采用的是哪一版价格
     * @param generatedAtEpochMillis 行情来源生成这份价格的 Unix 毫秒时间戳
     */
    public CoreMarkPriceState(String instrumentId, long markPriceTicks, long priceSequence,
                              long generatedAtEpochMillis) {
        this(instrumentId, markPriceTicks, 0, 0, priceSequence, generatedAtEpochMillis);
    }
}
