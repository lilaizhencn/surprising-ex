package com.surprising.gateway.c2c.model;

public record C2cConfiguration(boolean enabled, long escrowUserId, long feeUserId, int maxActiveAds, long revision) { }
