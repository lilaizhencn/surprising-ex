package com.surprising.trading.api.model;

import java.util.List;

public record AdminCancelOrdersResponse(
        int requested,
        int canceled,
        int skipped,
        List<AdminCancelOrderResult> results) {
}
