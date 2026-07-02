package com.surprising.account.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ProductBalanceAdjustmentRequest(
        @Positive long userId,
        @NotNull AccountType accountType,
        @NotBlank @Size(max = 20) String asset,
        long amountUnits,
        @NotBlank @Size(max = 128) String referenceId,
        @Size(max = 128) String reason) {
}
