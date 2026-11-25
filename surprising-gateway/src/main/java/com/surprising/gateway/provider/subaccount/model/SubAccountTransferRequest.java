package com.surprising.gateway.provider.subaccount.model;

import com.surprising.product.api.ProductLine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record SubAccountTransferRequest(
        @Positive long subUserId,
        boolean fromParentToSub,
        @NotBlank String asset,
        @Positive long amountUnits,
        ProductLine productLine) {
}
