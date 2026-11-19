package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;

public record C2cOffer(C2cAd ad, String nickname, String tier, boolean premiumVerified, boolean freezeCompensation,
                       long completed, long ended, long completedTotal,
                       BigDecimal paidSeconds, BigDecimal releasedSeconds) {
    public C2cOffer(C2cAd ad, String nickname, String tier, long completed, long ended, long completedTotal,
                    BigDecimal paidSeconds, BigDecimal releasedSeconds) {
        this(ad, nickname, tier, false, false, completed, ended, completedTotal, paidSeconds, releasedSeconds);
    }
}
