package com.surprising.gateway.provider.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Size(min = 3, max = 32) String username,
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 254) String email,
        @Size(max = 32) String phone,
        @Size(max = 64) String inviteCode) {
    public RegisterRequest(String username, String password, String email, String phone) {
        this(username, password, email, phone, null);
    }
    public RegisterRequest(String username, String password, String email) {
        this(username, password, email, null, null);
    }
}
