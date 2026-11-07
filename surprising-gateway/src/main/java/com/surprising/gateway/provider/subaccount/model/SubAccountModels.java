package com.surprising.gateway.provider.subaccount.model;

import com.surprising.product.api.ProductLine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class SubAccountModels {
    private SubAccountModels() {}

    public record CreateSubAccountRequest(
            @NotBlank @Size(min = 3, max = 32) String subAccountName,
            String permissions
    ) {}

    public record SubAccountRecord(
            long id,
            long parentUserId,
            long subUserId,
            String subAccountName,
            String permissions,
            String status,
            Instant createdAt
    ) {}

    public record SubAccountTransferRequest(
            @Positive long subUserId,
            boolean fromParentToSub,
            @NotBlank String asset,
            @Positive long amountUnits,
            ProductLine productLine
    ) {}

    public record SubAccountTransferResponse(
            String transferId,
            long fromUserId,
            long toUserId,
            String asset,
            long amountUnits,
            String status,
            Instant timestamp
    ) {}
}
