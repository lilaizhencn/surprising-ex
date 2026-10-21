package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;

public record C2cOffer(C2cAd ad, String nickname, String tier, long completed, long ended, long completedTotal,
                       BigDecimal paidSeconds, BigDecimal releasedSeconds) { }
