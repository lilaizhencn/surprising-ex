package com.surprising.trading.api.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BatchAmendOrdersRequest(
        @NotEmpty @Size(max = 20) List<@Valid AmendOrderRequest> orders) {

    public BatchAmendOrdersRequest {
        orders = orders == null ? List.of() : List.copyOf(orders);
    }
}
