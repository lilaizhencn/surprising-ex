package com.surprising.gateway.provider.repository;

import java.time.Instant;

public record UserDepositAddressRecord(
        long id,
        long userId,
        String networkCode,
        String addressFamily,
        String address,
        String memo,
        long addressVersion,
        Instant createdAt,
        Instant updatedAt) {
}
