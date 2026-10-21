package com.surprising.trading.api.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record BatchAmendOrdersRequest(
        @NotBlank @Size(max = 64) String batchKey,
        @NotEmpty @Size(max = 20) List<@Valid AmendOrderRequest> orders) {

    public BatchAmendOrdersRequest(List<AmendOrderRequest> orders) {
        this(legacyBatchKey(orders), orders);
    }

    public BatchAmendOrdersRequest {
        if (batchKey == null || batchKey.isBlank()) {
            throw new IllegalArgumentException("batchKey is required");
        }
        orders = orders == null ? List.of() : List.copyOf(orders);
    }

    private static String legacyBatchKey(List<?> orders) {
        return "legacy-" + UUID.nameUUIDFromBytes(String.valueOf(orders).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
