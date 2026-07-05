package com.surprising.trading.api.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BatchCancelOrdersRequest(
        @NotEmpty @Size(max = 50) List<@Valid CancelOrderRequest> orders) {

    public BatchCancelOrdersRequest {
        orders = orders == null ? List.of() : List.copyOf(orders);
    }
}
