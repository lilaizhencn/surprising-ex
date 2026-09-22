package com.surprising.trading.api.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BatchPlaceTriggerOrderRequest(
        @NotEmpty @Size(max = 20) List<@Valid PlaceTriggerOrderRequest> orders,
        Boolean atomic) {

    public BatchPlaceTriggerOrderRequest(List<PlaceTriggerOrderRequest> orders) {
        this(orders, false);
    }

    public BatchPlaceTriggerOrderRequest {
        orders = orders == null ? List.of() : List.copyOf(orders);
        atomic = Boolean.TRUE.equals(atomic);
    }
}
