package com.surprising.gateway.provider.auth.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @JsonAlias({"username", "email", "phone"}) String identifier,
        @NotBlank @Size(max = 128) String password) {
}
