package com.surprising.instrument.provider.model;

import java.math.BigDecimal;
import java.util.List;

/** 行情样本与聚合结果；来源于已关闭K线，不是在线盘口或账户状态。 */
public final class MarketSummaryRows {
    private MarketSummaryRows() { }
    public record Sample(int instrumentId, BigDecimal open, BigDecimal high, BigDecimal low,
                  BigDecimal close, BigDecimal volume, BigDecimal quoteVolume) {}

    public record Summary(BigDecimal lastPrice, BigDecimal change24h, BigDecimal high24h,
                          BigDecimal low24h, BigDecimal volume24h, BigDecimal quoteVolume24h,
                          List<BigDecimal> trend) {}
}
