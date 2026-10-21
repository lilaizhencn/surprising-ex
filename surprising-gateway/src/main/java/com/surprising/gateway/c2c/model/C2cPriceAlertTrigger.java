package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;
import java.util.UUID;

/** 本轮已锁定的预警及通知所需市场字段，仅在当前业务事务内使用。 */
public record C2cPriceAlertTrigger(UUID id, long userId, UUID marketId, String asset, String fiat,
                                  String side, BigDecimal targetPrice) { }
