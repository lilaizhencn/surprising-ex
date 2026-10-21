package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;
import java.util.UUID;

/** 市场配置与币种权威精度的只读投影，不保存第二份资产配置。 */
public record C2cMarket(UUID id, String asset, long assetScale, String fiat, int fiatDecimals, boolean enabled,
                        BigDecimal minFiat, BigDecimal maxFiat, BigDecimal minPrice, BigDecimal maxPrice,
                        int buyerFeePpm, int paymentWindowSeconds, int maxOpenOrders, boolean requireKyc,
                        int vipMinCompleted, long revision) { }
