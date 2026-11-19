package com.surprising.gateway.c2c.model;

import java.time.Instant;

public record C2cMerchant(long userId, String status, String tier, String application, String reviewReason,
                          String targetFiat, String targetAsset, boolean premiumVerified, boolean freezeCompensation,
                          String bdContact, long revision, Instant createdAt, Instant updatedAt) {
    public C2cMerchant(long userId, String status, String tier, String application, String reviewReason,
                       long revision, Instant createdAt, Instant updatedAt) {
        this(userId, status, tier, application, reviewReason, null, null, false, false, null, revision, createdAt, updatedAt);
    }
}
