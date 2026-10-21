package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;
import java.util.UUID;

/** Controller解析后的市场配置；资产精度仍从权威资产表读取，不能由请求指定。 */
public record C2cMarketChange(UUID marketId,long revision,String asset,String fiat,int fiatDecimals,boolean enabled,
                              BigDecimal minFiat,BigDecimal maxFiat,BigDecimal minPrice,BigDecimal maxPrice,int buyerFeePpm,
                              int paymentWindowSeconds,int maxOpenOrders,boolean requireKyc,int vipMinCompleted) { }
