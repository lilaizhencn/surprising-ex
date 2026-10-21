package com.surprising.gateway.c2c.model;

import java.time.Instant;

public record C2cMerchant(long userId, String status, String tier, String application, String reviewReason,
                          long revision, Instant createdAt, Instant updatedAt) { }
