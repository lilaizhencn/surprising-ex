package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Controller已校验并规范化的广告资料；市场额度、资格、精度和托管仍由Service决定。 */
public record C2cAdPublish(UUID marketId,Side side,BigDecimal price,BigDecimal quantity,BigDecimal minFiat,BigDecimal maxFiat,
                           List<String> methodCodes,List<UUID> paymentAccountIds,int paymentWindowSeconds,String terms,int minCompleted) {
    public enum Side { BUY, SELL }
}
