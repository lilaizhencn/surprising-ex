package com.surprising.insurance.api.model;

import java.util.List;

public record InsuranceLedgerQueryResponse(
        int count,
        List<InsuranceFundLedgerResponse> entries) {
}
