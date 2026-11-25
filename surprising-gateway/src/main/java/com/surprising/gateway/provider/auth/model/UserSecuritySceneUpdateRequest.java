package com.surprising.gateway.provider.auth.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UserSecuritySceneUpdateRequest(
        @NotNull Boolean enabled,
        @Size(min = 6, max = 6) String emailCode,
        @Size(min = 6, max = 6) String totpCode) {
}
