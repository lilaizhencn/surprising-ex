package com.surprising.account.api.model;

import java.util.List;

public record PendingProductTransfersResponse(List<ProductTransferOperationRequest> transfers) {

    public PendingProductTransfersResponse {
        transfers = List.copyOf(transfers);
    }
}
