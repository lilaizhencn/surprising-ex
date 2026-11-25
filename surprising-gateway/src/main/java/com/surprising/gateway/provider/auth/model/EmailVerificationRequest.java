package com.surprising.gateway.provider.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailVerificationRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(min = 6, max = 6) String code) {
}
