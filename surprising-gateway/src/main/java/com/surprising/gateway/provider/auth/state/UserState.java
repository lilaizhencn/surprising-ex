package com.surprising.gateway.provider.auth.state;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Valkey 中的用户访问状态（auth:user:{用户ID}）：请求校验需要的状态、角色、权限和被封的 IP/设备。
 * 有有效 API Key 或永久 Telegram 绑定的用户常驻；其余用户跟会话滑动过期。
 */
public record UserState(long userId, String username, String email, String displayName, Instant createdAt,
                        String status, List<String> roles, List<String> permissions,
                        Set<String> blockedIps, Set<String> blockedDevices, boolean hasApiKey, boolean telegramBound) {

    public UserState(long userId, String username, String email, String displayName, Instant createdAt,
                     String status, List<String> roles, List<String> permissions,
                     Set<String> blockedIps, Set<String> blockedDevices, boolean hasApiKey) {
        this(userId, username, email, displayName, createdAt, status, roles, permissions, blockedIps, blockedDevices, hasApiKey, false);
    }

    Map<String, String> toFields() {
        var fields = new HashMap<String, String>();
        if (username != null) fields.put("username", username);
        if (email != null) fields.put("email", email);
        if (displayName != null) fields.put("displayName", displayName);
        if (createdAt != null) fields.put("createdAt", Long.toString(createdAt.toEpochMilli()));
        fields.put("status", status == null ? "" : status);
        fields.put("roles", String.join(",", roles));
        fields.put("permissions", String.join(",", permissions));
        fields.put("blockedIps", String.join(",", blockedIps));
        fields.put("blockedDevices", String.join(",", blockedDevices));
        fields.put("hasApiKey", hasApiKey ? "1" : "0");
        fields.put("telegramBound", telegramBound ? "1" : "0");
        fields.put("persistent", hasApiKey || telegramBound ? "1" : "0");
        return fields;
    }

    static UserState fromFields(long userId, Map<String, String> fields) {
        String status=fields.get("status");
        if(status==null || !Set.of("NORMAL","FROZEN","TRADE_DISABLED","WITHDRAW_DISABLED").contains(status))
            throw new IllegalArgumentException("invalid user auth state");
        String createdAt = fields.get("createdAt");
        return new UserState(userId, fields.get("username"), fields.get("email"), fields.get("displayName"),
                createdAt == null ? null : Instant.ofEpochMilli(Long.parseLong(createdAt)),
                fields.get("status"), split(fields.get("roles")), split(fields.get("permissions")),
                new LinkedHashSet<>(split(fields.get("blockedIps"))),
                new LinkedHashSet<>(split(fields.get("blockedDevices"))), "1".equals(fields.get("hasApiKey")), "1".equals(fields.get("telegramBound")));
    }

    private static List<String> split(String value) {
        if (value == null || value.isEmpty()) return List.of();
        return Arrays.asList(value.split(","));
    }
}
