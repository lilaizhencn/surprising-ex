package com.surprising.gateway.provider.auth.model;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public record AdminUserStatusRequest(
        @NotBlank String status,
        String reason,
        Instant frozenUntil) {
    public AdminUserStatusRequest(String status) {
        this(status, null, null);
    }
}
