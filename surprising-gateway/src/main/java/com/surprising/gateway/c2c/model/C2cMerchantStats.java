package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;

public record C2cMerchantStats(long userId, String nickname, String status, String tier,
                               boolean premiumVerified, boolean freezeCompensation, long completed,
                               long ended, long total, BigDecimal paidSeconds, BigDecimal releasedSeconds) {
    public C2cMerchantStats(long userId, String nickname, String status, String tier, long completed,
                            long ended, long total, BigDecimal paidSeconds, BigDecimal releasedSeconds) {
        this(userId, nickname, status, tier, false, false, completed, ended, total, paidSeconds, releasedSeconds);
    }
}
