package com.surprising.gateway.provider.auth.model;

import java.util.List;

public record AdminRoleQueryResponse(
        int count,
        List<AdminRoleResponse> roles) {
}
