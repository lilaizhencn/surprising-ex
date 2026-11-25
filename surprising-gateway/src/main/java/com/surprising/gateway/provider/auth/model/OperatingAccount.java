package com.surprising.gateway.provider.auth.model;

public record OperatingAccount(
        long userId,
        String username,
        String displayName,
        String status,
        String subAccountStatus,
        String subAccountPermissions,
        boolean parent) {
}
