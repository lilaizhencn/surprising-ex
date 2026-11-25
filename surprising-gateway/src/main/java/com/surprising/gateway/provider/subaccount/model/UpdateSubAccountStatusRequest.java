package com.surprising.gateway.provider.subaccount.model;

import jakarta.validation.constraints.NotBlank;

public record UpdateSubAccountStatusRequest(@NotBlank String status) {
}
