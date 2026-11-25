package com.surprising.gateway.provider.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SensitiveChallengeVerificationRequest(
        @NotBlank String sceneCode,
        @NotBlank @Size(min = 6, max = 6) String emailCode,
        @Size(min = 6, max = 6) String totpCode) {
}
