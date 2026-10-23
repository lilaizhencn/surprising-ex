package com.surprising.aeron.service.market;

import com.surprising.aeron.service.instrument.CoreInstrument;

/**
 * 运行时的一版行情，使用整数币对编号并带上币对配置引用，供风险估值、成交准入及条件单扫描读取；这里不更新账户资金。
 * @param symbolId 运行时使用的非负币对整数编号；对外币对编号需要通过身份表换回
 * @param instrument 这份行情对应的币对配置引用，包含合约类型与价格金额精度
 * @param markPriceTicks 风险估值使用的标记价格，单位为价格 tick
 * @param indexPriceTicks 标的指数价格 tick，0 表示这份记录没有提供该价格
 * @param forwardPriceTicks 期权同到期远期价格 tick，其他产品固定为 0
 * @param priceSequence 行情的单调序号，用来识别扫描和估值采用的是哪一版价格
 * @param generatedAtEpochMillis 行情来源生成这份价格的 Unix 毫秒时间戳
 * @param lastPriceTicks 最新成交价格 tick，0 表示这份行情没有提供最新成交价
 */
public record MarkPriceRuntime(int symbolId, CoreInstrument instrument, long markPriceTicks,
                               long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                               long generatedAtEpochMillis, long lastPriceTicks) {
    /**
     * 创建尚未提供最新成交价的行情，lastPriceTicks 设为 0，其余行情和配置由调用方传入。
     * @param symbolId 运行时使用的非负币对整数编号；对外币对编号需要通过身份表换回
     * @param instrument 这份行情对应的币对配置引用，包含合约类型与价格金额精度
     * @param markPriceTicks 风险估值使用的标记价格，单位为价格 tick
     * @param indexPriceTicks 标的指数价格 tick，0 表示这份记录没有提供该价格
     * @param forwardPriceTicks 期权同到期远期价格 tick，其他产品固定为 0
     * @param priceSequence 行情的单调序号，用来识别扫描和估值采用的是哪一版价格
     * @param generatedAtEpochMillis 行情来源生成这份价格的 Unix 毫秒时间戳
     */
    public MarkPriceRuntime(int symbolId, CoreInstrument instrument, long markPriceTicks,
                            long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                            long generatedAtEpochMillis) {
        this(symbolId, instrument, markPriceTicks, indexPriceTicks, forwardPriceTicks,
                priceSequence, generatedAtEpochMillis, 0);
    }
    /**
     * 要求币对整数编号非负、配置引用存在，标记价、价格序号和生成时间为正；指数价、远期价及最新成交价允许用 0 表示未提供。
     */
    public MarkPriceRuntime {
        if (instrument != null && !instrument.contractType().isOption()) forwardPriceTicks = 0;
        if (symbolId < 0 || instrument == null || markPriceTicks <= 0
                || indexPriceTicks < 0 || forwardPriceTicks < 0 || lastPriceTicks < 0 || priceSequence <= 0
                || generatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid runtime mark price");
        }
    }

    /**
     * 创建只提供标记价的行情，指数价、远期价和最新成交价都设为 0。
     * @param symbolId 运行时使用的非负币对整数编号；对外币对编号需要通过身份表换回
     * @param instrument 这份行情对应的币对配置引用，包含合约类型与价格金额精度
     * @param markPriceTicks 风险估值使用的标记价格，单位为价格 tick
     * @param priceSequence 行情的单调序号，用来识别扫描和估值采用的是哪一版价格
     * @param generatedAtEpochMillis 行情来源生成这份价格的 Unix 毫秒时间戳
     */
    public MarkPriceRuntime(int symbolId, CoreInstrument instrument, long markPriceTicks, long priceSequence,
                            long generatedAtEpochMillis) {
        this(symbolId, instrument, markPriceTicks, 0, 0, priceSequence, generatedAtEpochMillis);
    }
}
