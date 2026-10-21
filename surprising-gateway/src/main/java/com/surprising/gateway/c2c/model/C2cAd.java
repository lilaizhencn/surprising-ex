package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record C2cAd(UUID id,long makerUserId,UUID marketId,String side,BigDecimal price,long totalUnits,long availableUnits,
              long reservedUnits,long completedUnits,long closedUnits,BigDecimal minFiat,BigDecimal maxFiat,
              List<String> methods,List<UUID> accounts,int paymentWindowSeconds,String terms,int minCompleted,
              String status,long revision) { }
