package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;

public record C2cMerchantStats(long userId, String nickname, String status, String tier, long completed,
                               long ended, long total, BigDecimal paidSeconds, BigDecimal releasedSeconds) { }
