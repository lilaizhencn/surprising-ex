package com.surprising.trading.api.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BatchCancelTriggerOrdersRequest(
        @NotEmpty @Size(max = 50) List<@Valid CancelTriggerOrderRequest> orders) {

    public BatchCancelTriggerOrdersRequest {
        orders = orders == null ? List.of() : List.copyOf(orders);
    }
}
