package com.surprising.gateway.provider.subaccount.model;

import java.time.Instant;

public record SubAccountRecord(
        long id,
        long parentUserId,
        long subUserId,
        String subAccountName,
        String permissions,
        String status,
        Instant createdAt) {
}
