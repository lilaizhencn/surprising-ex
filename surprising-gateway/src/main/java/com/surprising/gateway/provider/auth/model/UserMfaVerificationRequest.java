package com.surprising.gateway.provider.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserMfaVerificationRequest(
        @NotBlank @Size(max=128) String currentPassword,
        @NotBlank @Size(min = 6, max = 6) String totpCode) {
}
