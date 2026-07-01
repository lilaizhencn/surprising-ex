package com.surprising.insurance.api.model;

import java.util.List;

public record InsuranceCoverageQueryResponse(
        int count,
        List<InsuranceCoverageResponse> coverages) {
}
