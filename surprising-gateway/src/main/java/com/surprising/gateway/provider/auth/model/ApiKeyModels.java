package com.surprising.gateway.provider.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** API Key管理的请求和响应；秘密只出现在首次创建响应中。 */
public final class ApiKeyModels {
    private ApiKeyModels() { }

    public record CreateApiKeyRequest(@NotBlank @Size(max = 80) String label,
                                      List<String> permissions,
                                      List<String> ipAllowlist) {
    }

    public record RevokeApiKeyRequest(@NotBlank String apiKey) {
    }

    public record UpdateApiKeyRequest(@NotBlank String apiKey,
                                      @NotBlank @Size(max = 80) String label,
                                      @NotNull List<String> permissions, @NotNull List<String> ipAllowlist) {
    }
    public record CreatedApiKey(ApiKeyView apiKey, String secret) {
    }

    public record ApiKeyView(String apiKey, String label, String permissions, String status,
                             List<String> ipAllowlist, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
    }

}
