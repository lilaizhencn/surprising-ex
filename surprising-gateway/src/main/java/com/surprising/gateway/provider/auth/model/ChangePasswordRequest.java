package com.surprising.gateway.provider.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8, max = 128) String newPassword,
        @Size(min = 6, max = 6) String emailCode,
        @Size(min = 6, max = 6) String totpCode) {
}
