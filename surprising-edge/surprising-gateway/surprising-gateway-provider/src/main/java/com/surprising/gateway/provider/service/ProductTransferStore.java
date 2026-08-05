package com.surprising.gateway.provider.service;

public interface ProductTransferStore {

    ProductTransferState createOrGet(ProductTransferCreateRequest request);

    ProductTransferState lock(long transferId);

    ProductTransferState update(ProductTransferState state);
}
