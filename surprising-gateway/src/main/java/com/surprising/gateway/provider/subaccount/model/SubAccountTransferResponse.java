package com.surprising.gateway.provider.subaccount.model;

import java.time.Instant;

public record SubAccountTransferResponse(
        String transferId,
        long fromUserId,
        long toUserId,
        String asset,
        long amountUnits,
        String status,
        Instant timestamp) {
}
