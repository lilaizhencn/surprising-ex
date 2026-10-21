package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.util.UUID;

/** Creation receipt and latest parent state from committed realtime projections. */
public record AlgoOrderSubmissionResponse(long userId, ProductLine productLine,
        String clientAlgoOrderId, long algoOrderId, UUID commandId,
        String outcome, String code, AlgoOrderResponse algoOrder) { }
