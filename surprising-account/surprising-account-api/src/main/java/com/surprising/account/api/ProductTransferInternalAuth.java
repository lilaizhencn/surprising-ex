package com.surprising.account.api;

import com.surprising.account.api.model.PendingProductTransfersRequest;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import java.nio.charset.StandardCharsets;

public final class ProductTransferInternalAuth {

    public static final String SERVICE = "surprising-gateway";

    private ProductTransferInternalAuth() {
    }

    public static String canonical(String audience, long timestamp, ProductTransferOperationRequest request) {
        return field(SERVICE) + field(audience) + field(Long.toString(timestamp))
                + field(Long.toString(request.transferId())) + field(Long.toString(request.userId()))
                + field(request.sourceProductLine().name()) + field(request.targetProductLine().name())
                + field(request.sourceAccountType().name()) + field(request.targetAccountType().name())
                + field(request.asset()) + field(Long.toString(request.amountUnits()))
                + field(request.referenceId()) + field(request.reason() == null ? "" : request.reason());
    }

    public static String canonical(String audience, long timestamp, PendingProductTransfersRequest request) {
        return field(SERVICE) + field(audience) + field(Long.toString(timestamp))
                + field(request.productLine().name()) + field(Integer.toString(request.limit()));
    }

    private static String field(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length + ":" + value;
    }
}
