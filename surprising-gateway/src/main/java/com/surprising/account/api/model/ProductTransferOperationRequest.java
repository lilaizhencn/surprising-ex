package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ProductTransferOperationRequest(
        @Positive long transferId,
        @Positive long userId,
        @NotNull ProductLine sourceProductLine,
        @NotNull ProductLine targetProductLine,
        @NotNull AccountType sourceAccountType,
        @NotNull AccountType targetAccountType,
        @NotBlank @Size(max = 20) String asset,
        @Positive long amountUnits,
        @NotBlank @Size(max = 128) String referenceId,
        @Size(max = 256) String reason) {
}
