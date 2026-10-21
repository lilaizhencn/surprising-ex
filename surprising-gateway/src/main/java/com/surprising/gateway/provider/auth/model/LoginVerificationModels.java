package com.surprising.gateway.provider.auth.model;

import java.time.Instant;
import java.util.List;

/** 登录挑战与设置的请求/响应；保持与AuthModels.LoginResult同包的封闭类型边界。 */
public final class LoginVerificationModels {
    private LoginVerificationModels() { }
    public record BindingResponse(
            ChallengeResponse challenge, String secret, String provisioningUri, String qrCodeDataUrl) {}

    public record Setting(String type, boolean bound, boolean enabled, String destination) {}

    public record PrimaryAuthentication(boolean passwordRequired, boolean reauthenticationRequired,
                                        Instant authenticatedUntil) {}

    public record SettingRequest(
            @jakarta.validation.constraints.Size(max = 128)
                    String currentPassword,
            @jakarta.validation.constraints.Size(max = 254) String destination,
            boolean enabled) {}

    public record SettingConfirmation(
            @jakarta.validation.constraints.Size(max = 128)
                    String currentPassword,
            @jakarta.validation.Valid @jakarta.validation.constraints.NotNull
                    VerifyRequest codes) {}

    public record ResendRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64)
                    String challengeToken) {}

    public record Method(String type, String destination) {}

    public record ChallengeResponse(
            boolean requiresVerification,
            String challengeToken,
            Instant expiresAt,
            List<Method> methods,
            boolean simulated)
            implements AuthModels.LoginResult {}

    public record VerifyRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 128)
                    String challengeToken,
            @jakarta.validation.constraints.Size(max = 6) String emailCode,
            @jakarta.validation.constraints.Size(max = 6) String phoneCode,
            @jakarta.validation.constraints.Size(max = 6) String totpCode) {}

}
