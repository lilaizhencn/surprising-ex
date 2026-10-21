package com.surprising.account.api.model;

import java.util.List;

public record ProductBalanceQueryResponse(
        int count,
        List<ProductBalanceResponse> balances) {
}
