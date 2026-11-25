package com.surprising.gateway.provider.auth.model;

import jakarta.validation.constraints.NotBlank;

public record AdminUserStatusRequest(
        @NotBlank String status) {
}
