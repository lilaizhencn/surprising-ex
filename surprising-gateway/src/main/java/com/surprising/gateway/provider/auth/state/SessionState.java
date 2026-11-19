package com.surprising.gateway.provider.auth.state;

import java.util.HashMap;
import java.util.Map;

/**
 * Valkey 中的登录会话（auth:session:{会话ID}）。
 * 会话在最后一次请求后保留 ttlSeconds，期间有请求就续期（同一会话至少间隔 sessionRenewInterval 才续一次）。
 * 登录主体始终是 userId（主账号）；operatingUserId 是会话内当前查看/交易的账户，缺省等于 userId。
 */
public record SessionState(long sessionId, long userId, String deviceId,
                           long authenticatedAtMillis, long ttlSeconds, long renewedAtMillis,
                           String userAgent, String ipAddress, long createdAtMillis,
                           long operatingUserId) {

    public SessionState(long sessionId, long userId, String deviceId, long authenticatedAtMillis, long ttlSeconds, long renewedAtMillis) {
        this(sessionId, userId, deviceId, authenticatedAtMillis, ttlSeconds, renewedAtMillis, null, null, renewedAtMillis, userId);
    }

    public SessionState(long sessionId, long userId, String deviceId,
                        long authenticatedAtMillis, long ttlSeconds, long renewedAtMillis,
                        String userAgent, String ipAddress, long createdAtMillis) {
        this(sessionId, userId, deviceId, authenticatedAtMillis, ttlSeconds, renewedAtMillis, userAgent, ipAddress, createdAtMillis, userId);
    }

    public long effectiveOperatingUserId() {
        return operatingUserId > 0 ? operatingUserId : userId;
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
        fields.put("operatingUserId", Long.toString(effectiveOperatingUserId()));
        return fields;
    }

    static SessionState fromFields(long sessionId, Map<String, String> fields) {
        if (!fields.containsKey("uid") || !fields.containsKey("createdAt")
                || Long.parseLong(fields.getOrDefault("ttlSeconds", "0")) <= 0)
            throw new IllegalArgumentException("invalid session state");
        long userId = Long.parseLong(fields.get("uid"));
        String operating = fields.get("operatingUserId");
        long operatingUserId = operating == null || operating.isBlank() ? userId : Long.parseLong(operating);
        return new SessionState(sessionId, userId,
                fields.get("deviceId"), Long.parseLong(fields.getOrDefault("authenticatedAt", "0")),
                Long.parseLong(fields.getOrDefault("ttlSeconds", "0")),
                Long.parseLong(fields.getOrDefault("renewedAt", "0")), fields.get("userAgent"), fields.get("ipAddress"),
                Long.parseLong(fields.get("createdAt")), operatingUserId > 0 ? operatingUserId : userId);
    }

    SessionState renewedAt(long millis) {
        return new SessionState(sessionId, userId, deviceId, authenticatedAtMillis,
                ttlSeconds, millis, userAgent, ipAddress, createdAtMillis, effectiveOperatingUserId());
    }

    public SessionState withOperatingUserId(long operatingUserId) {
        return new SessionState(sessionId, userId, deviceId, authenticatedAtMillis,
                ttlSeconds, renewedAtMillis, userAgent, ipAddress, createdAtMillis,
                operatingUserId > 0 ? operatingUserId : userId);
    }
}
