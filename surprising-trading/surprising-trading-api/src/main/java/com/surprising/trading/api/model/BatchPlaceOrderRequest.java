package com.surprising.trading.api.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BatchPlaceOrderRequest(
        @NotEmpty @Size(max = 20) List<@Valid PlaceOrderRequest> orders) {

    public BatchPlaceOrderRequest {
        orders = orders == null ? List.of() : List.copyOf(orders);
    }
}
