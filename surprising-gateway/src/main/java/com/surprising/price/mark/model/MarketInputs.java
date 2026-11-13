package com.surprising.price.mark.model;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpTradeEvent;

/** 标记价格当前输入事实及可用性，不持有计算状态。 */
public record MarketInputs(IndexPriceEvent index,PerpBookTickerEvent book,PerpTradeEvent lastTrade,
                           boolean indexUsable,boolean bookUsable,boolean tradeUsable) {}
