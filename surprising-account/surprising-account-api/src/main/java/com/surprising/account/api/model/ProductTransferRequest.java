package com.surprising.account.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ProductTransferRequest(
        @Positive long userId,
        @NotNull AccountType sourceAccountType,
        @NotNull AccountType targetAccountType,
        @NotBlank @Size(max = 20) String asset,
        @Positive long amountUnits,
        @NotBlank @Size(max = 128) String referenceId,
        @Size(max = 128) String reason) {
}
