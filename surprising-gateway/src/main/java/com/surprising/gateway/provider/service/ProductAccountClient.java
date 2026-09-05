package com.surprising.gateway.provider.service;

import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.product.api.ProductLine;

public interface ProductAccountClient {

    ProductAccountAdjustment transferOut(String accountType, ProductTransferOperationRequest request);

    ProductAccountAdjustment transferIn(String accountType, ProductTransferOperationRequest request);

    ProductAccountAdjustment completeTransfer(String accountType, ProductTransferOperationRequest request);

    java.util.List<ProductTransferOperationRequest> pendingTransfers(ProductLine productLine, int limit);
}
