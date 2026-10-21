package com.surprising.gateway.provider.model;

import java.time.Instant;

public record UserDeviceStatus(long sessionId, String deviceId, String userAgent, String ipAddress,
                               Instant lastSeen, boolean active, boolean current, boolean blocked) { }
