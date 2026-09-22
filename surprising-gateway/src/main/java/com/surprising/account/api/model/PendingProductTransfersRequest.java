package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PendingProductTransfersRequest(
        @NotNull ProductLine productLine,
        @Min(1) @Max(256) int limit) {
}
