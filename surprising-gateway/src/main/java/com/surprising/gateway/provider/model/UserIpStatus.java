package com.surprising.gateway.provider.model;

import java.time.Instant;

public record UserIpStatus(String ipAddress, long loginCount, Instant lastSeen, boolean blocked) { }
