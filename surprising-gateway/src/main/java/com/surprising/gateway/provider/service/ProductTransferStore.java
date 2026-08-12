package com.surprising.gateway.provider.service;

public interface ProductTransferStore {

    ProductTransferState createOrGet(ProductTransferCreateRequest request);

    ProductTransferState lock(long transferId);

    ProductTransferState update(ProductTransferState previous, ProductTransferState next);

    java.util.List<ProductTransferState> recoverable(int limit);
}
