package com.surprising.account.api.model;

import java.util.List;

public record BalanceQueryResponse(
        int count,
        List<BalanceResponse> balances) {
}
