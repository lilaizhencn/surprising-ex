package com.surprising.gateway.provider.subaccount.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSubAccountRequest(
        @NotBlank @Size(min = 3, max = 32) String subAccountName,
        String permissions) {
}
