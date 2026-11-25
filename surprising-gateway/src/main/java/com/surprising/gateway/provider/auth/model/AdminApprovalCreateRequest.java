package com.surprising.gateway.provider.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminApprovalCreateRequest(
        @NotBlank String service,
        @NotBlank String httpMethod,
        @NotBlank String requestPath,
        String queryString,
        String requestBodySha256,
        @NotBlank @Size(max = 1000) String reason) {
}
