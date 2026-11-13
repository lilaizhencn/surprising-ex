package com.surprising.insurance.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InsuranceFundAdjustmentRequest(
        @NotBlank @Size(max = 20) String asset,
        long amountUnits,
        @NotBlank @Size(max = 128) String referenceId,
        @Size(max = 256) String reason) {
}
