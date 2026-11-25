package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record AdminRoleResponse(
        String roleCode,
        String roleName,
        int permissionCount,
        Instant createdAt) {
}
