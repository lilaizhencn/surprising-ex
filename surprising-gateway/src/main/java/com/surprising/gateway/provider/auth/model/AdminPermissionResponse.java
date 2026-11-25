package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record AdminPermissionResponse(
        String permissionCode,
        String permissionName,
        String description,
        Instant createdAt) {
}
