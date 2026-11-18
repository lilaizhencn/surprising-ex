package com.surprising.gateway.provider.auth.state;

import java.util.HashMap;
import java.util.Map;

/**
 * Valkey 中的登录会话（auth:session:{会话ID}）。
 * 会话在最后一次请求后保留 ttlSeconds，期间有请求就续期（同一会话至少间隔 sessionRenewInterval 才续一次）。
 */
public record SessionState(long sessionId, long userId, String deviceId,
                           long authenticatedAtMillis, long ttlSeconds, long renewedAtMillis,
                           String userAgent, String ipAddress, long createdAtMillis) {

    public SessionState(long sessionId, long userId, String deviceId, long authenticatedAtMillis, long ttlSeconds, long renewedAtMillis) {
        this(sessionId, userId, deviceId, authenticatedAtMillis, ttlSeconds, renewedAtMillis, null, null, renewedAtMillis);
    }

    Map<String, String> toFields() {
        var fields = new HashMap<String, String>();
        fields.put("uid", Long.toString(userId));
        if (deviceId != null) fields.put("deviceId", deviceId);
        fields.put("authenticatedAt", Long.toString(authenticatedAtMillis));
        fields.put("ttlSeconds", Long.toString(ttlSeconds));
        fields.put("renewedAt", Long.toString(renewedAtMillis));
        if (userAgent != null) fields.put("userAgent", userAgent);
        if (ipAddress != null) fields.put("ipAddress", ipAddress);
        fields.put("createdAt", Long.toString(createdAtMillis));
        return fields;
    }

    static SessionState fromFields(long sessionId, Map<String, String> fields) {
        if (!fields.containsKey("uid") || Long.parseLong(fields.getOrDefault("ttlSeconds", "0")) <= 0)
            throw new IllegalArgumentException("invalid session state");
        return new SessionState(sessionId, Long.parseLong(fields.get("uid")),
                fields.get("deviceId"), Long.parseLong(fields.getOrDefault("authenticatedAt", "0")),
                Long.parseLong(fields.getOrDefault("ttlSeconds", "0")),
                Long.parseLong(fields.getOrDefault("renewedAt", "0")), fields.get("userAgent"), fields.get("ipAddress"),
                Long.parseLong(fields.getOrDefault("createdAt", "0")));
    }

    SessionState renewedAt(long millis) {
        return new SessionState(sessionId, userId, deviceId, authenticatedAtMillis,
                ttlSeconds, millis, userAgent, ipAddress, createdAtMillis);
    }
}
