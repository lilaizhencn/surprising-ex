package com.surprising.gateway.provider.auth.model;

import java.util.List;

public record AdminRolePermissionsResponse(
        String roleCode,
        List<String> permissions) {
}
