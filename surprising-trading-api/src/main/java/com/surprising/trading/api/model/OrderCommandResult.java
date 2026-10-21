package com.surprising.trading.api.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "resultType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OrderResponse.class, name = "order"),
        @JsonSubTypes.Type(value = OrderBatchResponse.class, name = "order-batch"),
        @JsonSubTypes.Type(value = AmendOrderResponse.class, name = "amend"),
        @JsonSubTypes.Type(value = AmendOrderBatchResponse.class, name = "amend-batch")
})
public sealed interface OrderCommandResult
        permits OrderResponse, OrderBatchResponse, AmendOrderResponse, AmendOrderBatchResponse {
}
