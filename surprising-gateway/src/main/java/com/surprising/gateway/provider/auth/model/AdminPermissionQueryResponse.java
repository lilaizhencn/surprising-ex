package com.surprising.gateway.provider.auth.model;

import java.util.List;

public record AdminPermissionQueryResponse(
        int count,
        List<AdminPermissionResponse> permissions) {
}
